package grails.plugin.cache.redis

import grails.core.GrailsApplication
import grails.plugins.*
import grails.plugin.cache.redis.GrailsRedisCache
import grails.plugin.cache.redis.GrailsRedisCacheManager
import grails.plugin.cache.web.filter.redis.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.redis.cache.DefaultRedisCachePrefix
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.JedisShardInfo
import redis.clients.jedis.Protocol

/**
 * @author <a href='mailto:burt@burtbeckwith.com'>Burt Beckwith</a>
 */
class CacheRedisGrailsPlugin extends Plugin {

    private final Logger log = LoggerFactory.getLogger('grails.plugin.cache.CacheRedisGrailsPlugin')

    def grailsVersion = "3.0.0 > *"
    def loadAfter = ['cache']
    def pluginExcludes = [
            'grails-app/conf/*CacheConfig.groovy',
            'scripts/CreateCacheRedisTestApps.groovy',
            'docs/**',
            'src/docs/**'
    ]

    String title = 'Redis Cache Plugin'
    String description = 'A Redis-based implementation of the Cache plugin'

    def author = "Burt Beckwith"
    def authorEmail = "burt@burtbeckwith.com"

    def profiles = ['web']

    String documentation = 'http://grails-plugins.github.io/grails-cache-redis/'
    String license = 'APACHE'
    def developers = [
            [name: "Burt Beckwith", email: 'burt@burtbeckwith.com'],
            [name: 'Costin Leau'],
            [name: 'Colin Harrington', email:'colin.harrington@gmail.com']
    ]
    def issueManagement = [system: 'github', url: 'https://github.com/grails-plugins/grails-cache-redis/issues']
    def scm = [url: 'https://github.com/grails-plugins/grails-cache-redis']

    Closure doWithSpring() {
        {->
            def cacheConfig = grailsApplication.config.grails.cache
            def redisCacheConfig = cacheConfig.redis
			boolean pluginEnabled = (redisCacheConfig.enabled instanceof Boolean) ? redisCacheConfig.enabled : true
            if (!pluginEnabled) {
                log.warn 'Redis Cache plugin is disabled'
                return
            }

            int configDatabase = redisCacheConfig.database ?: 0
            boolean configUsePool = (redisCacheConfig.usePool instanceof Boolean) ? redisCacheConfig.usePool : true
            String configHostName = redisCacheConfig.hostName ?: 'localhost'
            int configPort = redisCacheConfig.port ?: Protocol.DEFAULT_PORT
            int configTimeout = redisCacheConfig.timeout ?: Protocol.DEFAULT_TIMEOUT
            String configPassword = redisCacheConfig.password ?: null
            Long ttlInSeconds = cacheConfig.ttl ?: GrailsRedisCache.NEVER_EXPIRE
            Boolean isUsePrefix = (redisCacheConfig.usePrefix instanceof Boolean) ? redisCacheConfig.usePrefix : false
            String keySerializerBean = redisCacheConfig.keySerializer instanceof String ?
                    redisCacheConfig.keySerializer : null
            String hashKeySerializerBean = redisCacheConfig.hashKeySerializer instanceof String ?
                    redisCacheConfig.hashKeySerializer : null

            grailsCacheJedisPoolConfig(JedisPoolConfig)

            grailsCacheJedisShardInfo(JedisShardInfo, configHostName, configPort) {
                password = configPassword
                connectionTimeout = configTimeout
            }

            grailsCacheJedisConnectionFactory(JedisConnectionFactory) {
                usePool = configUsePool
                database = configDatabase
                hostName = configHostName
                port = configPort
                timeout = configTimeout
                password = configPassword
                poolConfig = ref('grailsCacheJedisPoolConfig')
                shardInfo = ref('grailsCacheJedisShardInfo')
            }

            grailsRedisCacheSerializer(GrailsSerializer)

            grailsRedisCacheDeserializer(GrailsDeserializer)

            grailsRedisCacheDeserializingConverter(GrailsDeserializingConverter) {
                deserializer = ref('grailsRedisCacheDeserializer')
            }

            grailsRedisCacheSerializingConverter(GrailsSerializingConverter) {
                serializer = ref('grailsRedisCacheSerializer')
            }

            grailsCacheRedisSerializer(GrailsRedisSerializer) {
                serializer = ref('grailsRedisCacheSerializingConverter')
                deserializer = ref('grailsRedisCacheDeserializingConverter')
            }

            grailsCacheRedisKeySerializer(GrailsRedisKeySerializer, ref('grailsCacheRedisSerializer'))

            grailsCacheRedisTemplate(RedisTemplate) {
                connectionFactory = ref('grailsCacheJedisConnectionFactory')
                defaultSerializer = ref('grailsCacheRedisSerializer')
                if (keySerializerBean)
                    keySerializer = ref(keySerializerBean)
                if (hashKeySerializerBean)
                    hashKeySerializer = ref(hashKeySerializerBean)
            }

            String delimiter = redisCacheConfig.cachePrefixDelimiter ?: ':'
            redisCachePrefix(DefaultRedisCachePrefix, delimiter)

            grailsCacheManager(GrailsRedisCacheManager, ref('grailsCacheRedisTemplate')) {
                cachePrefix = ref('redisCachePrefix')
                timeToLive = ttlInSeconds
                usePrefix = isUsePrefix
            }

            grailsCacheFilter(RedisPageFragmentCachingFilter) {
                cacheManager = ref('grailsCacheManager')
                nativeCacheManager = ref('grailsCacheRedisTemplate')
                // TODO this name might be brittle - perhaps do by type?
                cacheOperationSource = ref('org.springframework.cache.annotation.AnnotationCacheOperationSource#0')
                keyGenerator = ref('webCacheKeyGenerator')
                expressionEvaluator = ref('webExpressionEvaluator')
            }
        }
    }

    private boolean isEnabled(GrailsApplication application) {
        //TODO cache plugin enabled and this one is..
        def enabled = application.config.grails.cache.enabled
        enabled == null || enabled != false
    }
}
