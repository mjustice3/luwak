package uk.co.flax.luwak;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;

class QueryIndex {
    
    private final IndexWriter writer;
    private final SearcherManager manager;

    /* Used to cache updates while a purge is ongoing */
    private volatile Map<BytesRef, QueryCacheEntry> purgeCache = null;

    /* Used to lock around the creation of the purgeCache */
    private final ReadWriteLock purgeLock = new ReentrantReadWriteLock();
    private final Object commitLock = new Object();

    /* The current query cache */
    private volatile ConcurrentMap<BytesRef, QueryCacheEntry> queries = new ConcurrentHashMap<>();
    // NB this is not final because it can be replaced by purgeCache()

    // package-private for testing
    final Map<IndexReader, QueryTermFilter> termFilters = new HashMap<>();
    
    QueryIndex(IndexWriter indexWriter) throws IOException {
        this.writer = indexWriter;
        this.manager = new SearcherManager(writer, true, true, new TermsHashBuilder());
    }
    
    QueryIndex() throws IOException {
        this(Monitor.defaultIndexWriter(new RAMDirectory()));
    }

    private class TermsHashBuilder extends SearcherFactory {
        @Override
        public IndexSearcher newSearcher(IndexReader reader, IndexReader previousReader) throws IOException {
            IndexSearcher searcher = super.newSearcher(reader, previousReader);
            searcher.setQueryCache(null);
            termFilters.put(reader, new QueryTermFilter(reader));
            reader.addReaderClosedListener(termFilters::remove);
            return searcher;
        }
    }

    void commit(List<Indexable> updates) throws IOException {
        synchronized (commitLock) {
            purgeLock.readLock().lock();
            try {
                if (updates != null) {
                    Set<String> ids = new HashSet<>();
                    for (Indexable update : updates) {
                        ids.add(update.id);
                    }
                    for (String id : ids) {
                        writer.deleteDocuments(new Term(Monitor.FIELDS.del, id));
                    }
                    for (Indexable update : updates) {
                        this.queries.put(update.queryCacheEntry.hash, update.queryCacheEntry);
                        writer.addDocument(update.document);
                        if (purgeCache != null)
                            purgeCache.put(update.queryCacheEntry.hash, update.queryCacheEntry);
                    }
                }
                writer.commit();
                manager.maybeRefresh();
            } finally {
                purgeLock.readLock().unlock();
            }
        }
    }

    interface QueryBuilder {
        Query buildQuery(QueryTermFilter termFilter) throws IOException;
    }

    long scan(QueryCollector matcher) throws IOException {
        return search(new MatchAllDocsQuery(), matcher);
    }

    long search(final Query query, QueryCollector matcher) throws IOException {
        QueryBuilder builder = termFilter -> query;
        return search(builder, matcher);
    }

    long search(QueryBuilder queryBuilder, QueryCollector matcher) throws IOException {
        IndexSearcher searcher = null;
        try {
            Map<BytesRef, QueryCacheEntry> queries;

            purgeLock.readLock().lock();
            try {
                searcher = manager.acquire();
                queries = this.queries;
            }
            finally {
                purgeLock.readLock().unlock();
            }

            MonitorQueryCollector collector = new MonitorQueryCollector(queries, matcher);
            long buildTime = System.nanoTime();
            Query query = queryBuilder.buildQuery(termFilters.get(searcher.getIndexReader()));
            buildTime = System.nanoTime() - buildTime;
            searcher.search(query, collector);
            return buildTime;
        }
        finally {
            if (searcher != null) {
                manager.release(searcher);
            }
        }
    }
    
    interface CachePopulator {
        void populateCacheWithIndex(Map<BytesRef, QueryCacheEntry> newCache) throws IOException;
    }
    
    /**
     * Remove unused queries from the query cache.
     *
     * This is normally called from a background thread at a rate set by configurePurgeFrequency().
     *
     * @throws IOException on IO errors
     */
    synchronized void purgeCache(CachePopulator populator) throws IOException {

        /*
            Note on implementation

            The purge works by scanning the query index and creating a new query cache populated
            for each query in the index.  When the scan is complete, the old query cache is swapped
            for the new, allowing it to be garbage-collected.

            In order to not drop cached queries that have been added while a purge is ongoing,
            we use a ReadWriteLock to guard the creation and removal of an update log.  Commits take
            the read lock.  If the update log has been created, then a purge is ongoing, and queries
            are added to the update log within the read lock guard.

            The purge takes the write lock when creating the update log, and then when swapping out
            the old query cache.  Within the second write lock guard, the contents of the update log
            are added to the new query cache, and the update log itself is removed.
         */

        final ConcurrentMap<BytesRef, QueryCacheEntry> newCache = new ConcurrentHashMap<>();

        purgeLock.writeLock().lock();
        try {
            purgeCache = new ConcurrentHashMap<>();
        }
        finally {
            purgeLock.writeLock().unlock();
        }
        
        populator.populateCacheWithIndex(newCache);

        purgeLock.writeLock().lock();
        try {
            newCache.putAll(purgeCache);
            purgeCache = null;
            queries = newCache;
        }
        finally {
            purgeLock.writeLock().unlock();
        }
    }
    
    
    // ---------------------------------------------
    //  Proxy trivial operations...
    // ---------------------------------------------

    void closeWhileHandlingException() throws IOException {
        IOUtils.closeWhileHandlingException(manager, writer, writer.getDirectory());
    }

    int numDocs() {
        return writer.numDocs();
    }

    int numRamDocs() {
        return writer.numRamDocs();
    }

    int cacheSize() {
        return queries.size();
    }

    void deleteDocuments(Term term) throws IOException {
        writer.deleteDocuments(term);
    }

    void deleteDocuments(Query query) throws IOException {
        writer.deleteDocuments(query);            
    }

    interface QueryCollector {

        void matchQuery(String id, QueryCacheEntry query, DataValues dataValues) throws IOException;

    }

    // ---------------------------------------------
    //  Helper classes...
    // ---------------------------------------------

    static final class DataValues {
        public BinaryDocValues hash;
        public SortedDocValues id;
        public BinaryDocValues mq;
        public Scorer scorer;
        public int doc;
    }

    /**
     * A Collector that decodes the stored query for each document hit.
     */
    static final class MonitorQueryCollector extends SimpleCollector {

        private final Map<BytesRef, QueryCacheEntry> queries;
        private final QueryCollector matcher;
        private final DataValues dataValues = new DataValues();

        public MonitorQueryCollector(Map<BytesRef, QueryCacheEntry> queries, QueryCollector matcher) {
            this.queries = queries;
            this.matcher = matcher;
        }

        @Override
        public void setScorer(Scorer scorer) throws IOException {
            this.dataValues.scorer = scorer;
        }

        @Override
        public void collect(int doc) throws IOException {
            BytesRef hash = dataValues.hash.get(doc);
            BytesRef id = dataValues.id.get(doc);
            QueryCacheEntry query = queries.get(hash);
            dataValues.doc = doc;
            matcher.matchQuery(id.utf8ToString(), query, dataValues);
        }

        @Override
        public void doSetNextReader(LeafReaderContext context) throws IOException {
            this.dataValues.hash = context.reader().getBinaryDocValues(Monitor.FIELDS.hash);
            this.dataValues.id = context.reader().getSortedDocValues(Monitor.FIELDS.id);
            this.dataValues.mq = context.reader().getBinaryDocValues(Monitor.FIELDS.mq);
        }

        @Override
        public boolean needsScores() {
            return false;
        }

    }
}
