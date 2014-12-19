package com.googlecode.cqengine.collection.impl;

import com.googlecode.cqengine.engine.QueryEngineInternal;
import com.googlecode.cqengine.index.common.Factory;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOption;
import com.googlecode.cqengine.resultset.closeable.CloseableResultSet;
import com.googlecode.cqengine.resultset.filter.FilteringResultSet;
import com.googlecode.cqengine.resultset.iterator.IteratorUtil;

import java.util.*;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

import static com.googlecode.cqengine.query.QueryFactory.all;
import static com.googlecode.cqengine.query.option.IsolationLevel.READ_UNCOMMITTED;
import static com.googlecode.cqengine.query.option.IsolationOption.isIsolationLevel;

/**
 * Extends {@link ConcurrentIndexedCollection} with support for READ_COMMITTED transaction isolation using
 * <a href="http://en.wikipedia.org/wiki/Multiversion_concurrency_control">Multiversion concurrency control</a>
 * (MVCC).
 * <p/>
 * <b>This class is feature complete but needs further testing!</b>
 *
 * @author Niall Gallagher
 */
public class TransactionalIndexedCollection<O> extends ConcurrentIndexedCollection<O> {

    final Class<O> objectType;
    final AtomicLong versionGenerator = new AtomicLong();
    volatile long currentVersion = 0;
    final ConcurrentNavigableMap<Long, Version> versions = new ConcurrentSkipListMap<Long, Version>();
    final Object writeMutex = new Object();

    static class Version<O> {
        final AtomicLong readersCount = new AtomicLong();
        final Semaphore writeLock = new Semaphore(0, true);
        final Iterable<O> objectsToExclude;

        Version(Iterable<O> objectsToExclude) {
            this.objectsToExclude = objectsToExclude;
        }
    }

    /**
     * {@inheritDoc}
     */
    public TransactionalIndexedCollection(Class<O> objectType, Factory<Set<O>> backingSetFactory, QueryEngineInternal<O> queryEngine) {
        super(backingSetFactory, queryEngine);
        this.objectType = objectType;
        // Set up initial version...
        incrementVersion(Collections.<O>emptySet());
    }

    /**
     * {@inheritDoc}
     * <p/>
     * This method applies multi-version concurrency control by default such that update is seen to occur with
     * {@code READ_COMMITTED} transaction isolation by reading threads.
     * <p/>
     * However optionally for performance reasons, this may be overridden on a case-by-case basis, by supplying an
     * {@link com.googlecode.cqengine.query.option.IsolationOption} to this method requesting
     * {@link com.googlecode.cqengine.query.option.IsolationLevel#READ_UNCOMMITTED} transaction isolation instead.
     * In that case the modifications will be made directly to the collection, bypassing multi-version concurrency
     * control. This might be useful when making some modifications to the collection which do not need to be viewed
     * atomically.
     */
    @Override
    public boolean update(Iterable<O> objectsToRemove, Iterable<O> objectsToAdd) {
        return update(objectsToRemove, objectsToAdd, Collections.<Class<? extends QueryOption>, QueryOption<O>>emptyMap());
    }

    /**
     * {@inheritDoc}
     * <p/>
     * This method applies multi-version concurrency control by default such that update is seen to occur with
     * {@code READ_COMMITTED} transaction isolation by reading threads.
     * <p/>
     * However optionally for performance reasons, this may be overridden on a case-by-case basis, by supplying an
     * {@link com.googlecode.cqengine.query.option.IsolationOption} to this method requesting
     * {@link com.googlecode.cqengine.query.option.IsolationLevel#READ_UNCOMMITTED} transaction isolation instead.
     * In that case the modifications will be made directly to the collection, bypassing multi-version concurrency
     * control. This might be useful when making some modifications to the collection which do not need to be viewed
     * atomically.
     */
    @Override
    public boolean update(final Iterable<O> objectsToRemove, final Iterable<O> objectsToAdd, Map<Class<? extends QueryOption>, QueryOption<O>> queryOptions) {
        if (isIsolationLevel(queryOptions, READ_UNCOMMITTED)) {
            // Write directly to the collection with no MVCC overhead...
            return super.update(objectsToRemove, objectsToAdd, queryOptions);
        }
        // Otherwise apply MVCC to support READ_COMMITTED isolation...
        synchronized (writeMutex) {
            Iterator<O> objectsToRemoveIterator = objectsToRemove.iterator();
            Iterator<O> objectsToAddIterator = objectsToAdd.iterator();
            if (!objectsToRemoveIterator.hasNext() && !objectsToAddIterator.hasNext()) {
                return false;
            }
            boolean modified = false;
            if (objectsToAddIterator.hasNext()) {
                // Configure new reading threads to exclude the objects we will add...
                incrementVersion(objectsToAdd);

                // Wait for this to take effect across all threads...
                waitForReadersOfPreviousVersionsToFinish();

                // Now add the given objects...
                modified = doAddAll(objectsToAdd);
            }
            if (objectsToRemoveIterator.hasNext()) {
                // Configure (or reconfigure) new reading threads to (instead) exclude the objects we will remove...
                incrementVersion(objectsToRemove);

                // Wait for this to take effect across all threads...
                waitForReadersOfPreviousVersionsToFinish();

                // Now remove the given objects...
                modified = doRemoveAll(objectsToRemove) || modified;
            }

            // Finally, remove the exclusion...
            incrementVersion(Collections.<O>emptySet());

            // Wait for this to take effect across all threads...
            waitForReadersOfPreviousVersionsToFinish();

            return modified;
        }
    }

    void incrementVersion(Iterable<O> objectsToExcludeFromThisVersion) {
        // Note we add the new Version object to the map before we update currentVersion,
        // to prevent a race condition where a reader could read the incremented version
        // but the object would not yet be in the map...
        long nextVersion = versionGenerator.incrementAndGet();
        versions.put(nextVersion, new Version<O>(objectsToExcludeFromThisVersion));
        currentVersion = nextVersion;
    }

    void waitForReadersOfPreviousVersionsToFinish() {
        Collection<Version> previousVersions = versions.headMap(versions.lastKey()).values();
        for (Iterator<Version> previousVersionsIterator = previousVersions.iterator(); previousVersionsIterator.hasNext(); ) {
            Version previousVersion = previousVersionsIterator.next();
            // Wait until the last reader (if there is one) of this previous version signals that it has finished...
            if (previousVersion.readersCount.get() != 0) {
                previousVersion.writeLock.acquireUninterruptibly();
            }

            // At this point readers of this previous version have finished.
            // Remove this version from memory...
            previousVersionsIterator.remove();
        }
    }

    @Override
    public boolean add(O o) {
        return update(Collections.<O>emptySet(), Collections.singleton(o));
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public boolean remove(Object object) {
        return update(Collections.singleton((O) object), Collections.<O>emptySet());
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public boolean addAll(Collection<? extends O> c) {
        return update(Collections.<O>emptySet(), (Collection<O>) c);
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public boolean removeAll(Collection<?> c) {
        return update((Collection<O>) c, Collections.<O>emptySet());
    }

    @Override
    public boolean retainAll(final Collection<?> c) {
        CloseableResultSet<O> allObjects = null;
        try {
            allObjects = retrieve(all(objectType)); // TODO validate locking
            FilteringResultSet<O> subsetToRemove = new FilteringResultSet<O>(allObjects) {
                @Override
                public boolean isValid(O object) {
                    return !c.contains(object);
                }
            };
            return update(subsetToRemove, Collections.<O>emptySet());
        }
        finally {
            if (allObjects != null) {
                allObjects.close();
            }
        }
    }

    @Override
    public synchronized void clear() {
        retainAll(Collections.<Object>emptySet());
    }

    @Override
    public CloseableResultSet<O> retrieve(Query<O> query) {
        return retrieve(query, Collections.<Class<? extends QueryOption>, QueryOption<O>>emptyMap());
    }

    @Override
    public CloseableResultSet<O> retrieve(Query<O> query, Map<Class<? extends QueryOption>, QueryOption<O>> queryOptions) {
        if (isIsolationLevel(queryOptions, READ_UNCOMMITTED)) {
            // Allow the query to read directly from the collection with no filtering overhead...
            return new CloseableResultSet<O>(super.retrieve(query, queryOptions)) {
                @Override
                public boolean isValid(O object) {
                    return true;
                }

                @Override
                public void close() {
                    super.close();
                }
            };
        }
        // Otherwise apply READ_COMMITTED isolation...

        // Get the Version object for the current version of the collection...
        final long thisVersionNumber = currentVersion;
        final Version thisVersion = versions.get(thisVersionNumber);
        // Increment the readers count to record that this thread is reading this version...
        thisVersion.readersCount.incrementAndGet();
        // Return the results matching the query such that:
        // - We filter out from the results any objects which might not be fully committed yet
        //   (as configured by writing threads for this version of the collection).
        // - When the ResultSet.close() method is called, we decrement the readers count
        //   to record that this thread is no longer reading this version.
        return new CloseableResultSet<O>(super.retrieve(query, queryOptions)) {
            @Override
            public boolean isValid(O object) {
                return !iterableOrCollectionContains(thisVersion.objectsToExclude, object);
            }

            @Override
            public void close() {
                super.close();
                // Decrement the readers count for this version...
                long decrementedReadersCountForThisVersion = thisVersion.readersCount.decrementAndGet();
                if (decrementedReadersCountForThisVersion == 0) {
                    // Readers count is zero so there are no other concurrent readers of this version *right now*.
                    // If the version of the collection *has not changed* since this thread started reading,
                    // then this is still the latest version of the collection, and it is possible that
                    // another thread will start reading from this version and increment the count above zero again
                    // after this thread finishes.
                    // OTOH, if the version of the collection *has changed* since this thread started reading,
                    // then no new threads can ever start reading from this version again, AND this is
                    // the last thread to finish reading this version.

                    if (thisVersionNumber != currentVersion) {
                        // This is the last thread to finish reading this version.
                        // Release the write lock, which notifies writing threads that this last reading thread has
                        // finished reading this version...
                        thisVersion.writeLock.release();
                    }
                }
            }
        };
    }

    boolean doAddAll(Iterable<O> objects) {
        if (objects instanceof Collection) {
            return super.addAll((Collection<O>) objects);
        }
        else {
            boolean modified = false;
            for (O object : objects) {
                modified = super.add(object) || modified;
            }
            return modified;
        }
    }

    boolean doRemoveAll(Iterable<O> objects) {
        if (objects instanceof Collection) {
            return super.removeAll((Collection<O>) objects);
        } else {
            boolean modified = false;
            for (O object : objects) {
                modified = super.remove(object) || modified;
            }
            return modified;
        }
    }

    static <O> boolean iterableOrCollectionContains(Iterable<O> objects, O o) {
        return (objects instanceof Collection)
                ? ((Collection<?>) objects).contains(o)
                : IteratorUtil.iterableContains(objects, o);
    }
}