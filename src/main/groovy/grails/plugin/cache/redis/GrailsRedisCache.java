/* Copyright 2012 SpringSource.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugin.cache.redis;

import grails.plugin.cache.GrailsCache;
import grails.plugin.cache.GrailsValueWrapper;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.Assert;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Based on package-scope org.springframework.data.redis.cache.RedisCache.
 *
 * @author Costin Leau
 * @author Burt Beckwith
 */
public class GrailsRedisCache implements GrailsCache {

    public static final long NEVER_EXPIRE = 0;

    protected static final int PAGE_SIZE = 128;

    protected final String name;
    @SuppressWarnings("rawtypes")
    protected final RedisTemplate template;
    protected final byte[] prefix;
    protected final byte[] setName;
    protected final byte[] cacheLockName;
    protected long WAIT_FOR_LOCK = 300;
    protected long ttl;

    /**
     * Constructor.
     *
     * @param name        cache name
     * @param prefix
     * @param template
     * @param ttl
     */
    public GrailsRedisCache(String name, byte[] prefix, RedisTemplate<? extends Object, ? extends Object> template, Long ttl) {
        Assert.hasText(name, "non-empty cache name is required");

        this.name = name;
        this.template = template;
        this.prefix = prefix;
        this.ttl = ttl == null ? NEVER_EXPIRE : ttl.longValue();

        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        // name of the set holding the keys
        setName = stringSerializer.serialize(name + "~keys");
        cacheLockName = stringSerializer.serialize(name + "~lock");
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation simply returns the RedisTemplate used for configuring
     * the cache, giving access to the underlying Redis store.
     */
    @Override
    public Object getNativeCache() {
        return template;
    }

    @SuppressWarnings("unchecked")
    @Override
    public ValueWrapper get(final Object key) {
        return (ValueWrapper) template.execute(new RedisCallback<ValueWrapper>() {
            public ValueWrapper doInRedis(RedisConnection connection) throws DataAccessException {
                waitForLock(connection);
                byte[] bs = connection.get(computeKey(key));
                return (bs == null ? null : newValueWrapper(template.getValueSerializer().deserialize(bs)));
            }
        }, true);
    }

    @Override
    public <T> T get(final Object key, Class<T> type) {
        return (T) template.execute(new RedisCallback<T>() {
            public T doInRedis(RedisConnection connection) throws DataAccessException {
                waitForLock(connection);
                byte[] bs = connection.get(computeKey(key));
                return (T) template.getValueSerializer().deserialize(bs);
            }
        }, true);
    }

	@Override
	public <T> T get(final Object key, Callable<T> valueLoader) {
		/*
		 * FIXME: I had to add this method override in order to satisfy
		 * the Spring Cache interface. It looks like this method signature
		 * including the Callable parameter was added sometime after the
		 * original cache-redis plugin was developed (?).
		 */
		return (T) this.get(key);
	}

    @SuppressWarnings("unchecked")
    @Override
    public void put(final Object key, final Object value) {
        final byte[] k = computeKey(key);
        template.execute(new RedisCallback<Object>() {
            public Object doInRedis(RedisConnection connection) throws DataAccessException {
                waitForLock(connection);
                connection.multi();
                connection.set(k, template.getValueSerializer().serialize(value));
                connection.zAdd(setName, 0, k);

                // Set key time to live when expiration has been configured.
                if (ttl > NEVER_EXPIRE) {
                    connection.expire(k, ttl);
                    connection.expire(setName, ttl);
                }

                connection.exec();
                return null;
            }
        }, true);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ValueWrapper putIfAbsent(final Object key, final Object value) {
        final byte[] k = computeKey(key);
        return (ValueWrapper) template.execute(new RedisCallback<ValueWrapper>() {
            public ValueWrapper doInRedis(RedisConnection connection) throws DataAccessException {
                waitForLock(connection);
                byte[] bs = connection.get(computeKey(key));

                if (bs == null) {
                    connection.multi();
                    connection.set(k, template.getValueSerializer().serialize(value));
                    connection.zAdd(setName, 0, k);

                    // Set key time to live when expiration has been configured.
                    if (ttl > NEVER_EXPIRE) {
                        connection.expire(k, ttl);
                        connection.expire(setName, ttl);
                    }

                    connection.exec();
                }

                bs = connection.get(computeKey(key));
                return (bs == null ? null : newValueWrapper(template.getValueSerializer().deserialize(bs)));
            }
        }, true);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void evict(Object key) {
        final byte[] k = computeKey(key);
        template.execute(new RedisCallback<Object>() {
            public Object doInRedis(RedisConnection connection) throws DataAccessException {
                connection.del(k);
                // remove key from set
                connection.zRem(setName, k);
                return null;
            }
        }, true);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void clear() {
        // need to del each key individually
        template.execute(new RedisCallback<Object>() {
            public Object doInRedis(RedisConnection connection) throws DataAccessException {
                // another clear is on-going
                if (connection.exists(cacheLockName)) {
                    return null;
                }

                try {
                    connection.set(cacheLockName, cacheLockName);

                    int offset = 0;
                    boolean finished = false;

                    do {
                        // need to paginate the keys
                        Set<byte[]> keys = connection.zRange(setName, (offset) * PAGE_SIZE, (offset + 1) * PAGE_SIZE - 1);
                        finished = keys.size() < PAGE_SIZE;
                        offset++;
                        if (!keys.isEmpty()) {
                            connection.del(keys.toArray(new byte[keys.size()][]));
                        }
                    }
                    while (!finished);

                    connection.del(setName);
                    return null;

                } finally {
                    connection.del(cacheLockName);
                }
            }
        }, true);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Collection<Object> getAllKeys() {
        Set<byte[]> serializedKeys = (Set<byte[]>) template.execute(new RedisCallback<Set<byte[]>>() {
            public Set<byte[]> doInRedis(RedisConnection connection) throws DataAccessException {
                Set<byte[]> allKeys = new HashSet<byte[]>();
                int offset = 0;
                boolean finished = false;
                while (!finished) {
                    // need to paginate the keys
                    Set<byte[]> keys = connection.zRange(setName, (offset) * PAGE_SIZE, (offset + 1) * PAGE_SIZE - 1);
                    allKeys.addAll(keys);
                    finished = keys.size() < PAGE_SIZE;
                    offset++;
                }
                return allKeys;
            }
        }, true);

        @SuppressWarnings("rawtypes")
        Collection<Object> keys = new HashSet(serializedKeys.size());
        RedisSerializer<byte[]> keySerializer = template.getKeySerializer();
        for (byte[] bytes : serializedKeys) {
            keys.add(keySerializer.deserialize(bytes));
        }

        return keys;
    }

    public byte[] computeKey(Object key) {
        @SuppressWarnings("unchecked")
        byte[] k = template.getKeySerializer().serialize(key);

        if (prefix == null || prefix.length == 0) {
            return k;
        }

        // ok to use Arrays.copyOf since spring-data-redis requires Java 6
        byte[] result = Arrays.copyOf(prefix, prefix.length + k.length);
        System.arraycopy(k, 0, result, prefix.length, k.length);
        return result;
    }

    protected GrailsValueWrapper newValueWrapper(Object value) {
        return value == null ? null : new GrailsValueWrapper(value, null);
    }

    protected boolean waitForLock(RedisConnection connection) {
        boolean foundLock = false;
        boolean retry = true;
        while (retry) {
            retry = false;
            if (connection.exists(cacheLockName)) {
                foundLock = true;
                try {
                    Thread thread = Thread.currentThread();

                    synchronized (thread) {
                        thread.wait(WAIT_FOR_LOCK);
                    }
                } catch (InterruptedException ex) {
                    // ignore
                }
                retry = true;
            }
        }
        return foundLock;
    }

}
