package at.rocworks.gateway.core.service

import at.rocworks.gateway.core.cache.OpcNode
import at.rocworks.gateway.core.cache.OpcValue
import at.rocworks.gateway.core.data.Topic
import io.reactivex.Observable
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.core.spi.cluster.ClusterManager
import io.vertx.spi.cluster.ignite.IgniteClusterManager
import org.apache.ignite.Ignite
import org.apache.ignite.IgniteCache
import org.apache.ignite.binary.BinaryObject
import org.apache.ignite.configuration.CacheConfiguration
import org.apache.ignite.events.CacheEvent
import org.apache.ignite.events.EventType
import org.apache.ignite.lang.IgniteBiPredicate
import org.apache.ignite.lang.IgnitePredicate
import java.util.*

object ClusterCache {
    private const val cacheName = "SCADA"

    private var manager: ClusterManager? = null

    private var cache: IgniteCache<String, Any>? = null

    private var vertx: Vertx? = null

    fun init(manager: ClusterManager, vertx: Vertx) {
        /*
          select table_name from information_schema.tables where table_schema='PUBLIC';
          select column_name, data_type, type_name, column_type from information_schema.columns where table_name='OPCVALUES';
         */
        this.vertx = vertx
        if (manager is IgniteClusterManager) {
            this.manager = manager
            this.cache = getCache(manager)
        }
    }

    private fun getCache(manager: IgniteClusterManager): IgniteCache<String, Any> {
        val config = CacheConfiguration<String, Any>()
        config.name = cacheName
        config.sqlIndexMaxInlineSize = 100 // TODO: should be configurable
        config.setIndexedTypes(
            String::class.java, OpcNode::class.java,
            String::class.java, OpcValue::class.java
        )
        return manager.igniteInstance.getOrCreateCache(config)
    }

    fun isEnabled() = cache != null

    fun put(key: String, value: Any) {
        cache?.let {
            it.put(key, value)
        }
    }

    fun registerSystem(systemType: Topic.SystemType, systemName: String) {
        val manager = this.manager
        if (manager is IgniteClusterManager) {
            try {
                // This optional local callback is called for each event notification that passed remote predicate listener.
                val localListener: IgniteBiPredicate<UUID, CacheEvent> = IgniteBiPredicate { _, e ->
                    try {
                        if (e.hasNewValue()) {
                            val value = e.newValue()
                            if (value is BinaryObject && value.type().typeName() == OpcValue::class.qualifiedName) {
                                val opcValue = value.deserialize<OpcValue>()
                                val data = JsonObject()
                                    .put("NodeId", opcValue.nodeId)
                                    .put("Value", opcValue.updateValue)
                                vertx?.eventBus()?.publish("${systemType.name}/$systemName/Write", data)
                            }
                        }
                    } catch (e: java.lang.Exception) {
                        e.printStackTrace()
                    }
                    true
                }

                val remoteFilter =
                    IgnitePredicate<CacheEvent> { e ->
                        try {
                            when (val value = e.newValue()) {
                                is BinaryObject -> {
                                    (value.type().typeName() == OpcValue::class.qualifiedName &&
                                            value.field<String>("updateValue") != null &&
                                            value.field<String>("systemType") == systemType.name &&
                                            value.field<String>("systemName") == systemName)
                                }
                                is OpcValue -> {
                                    (value.updateValue != null &&
                                            value.systemType == systemType.name &&
                                            value.systemName == systemName)
                                }
                                else -> false
                            }
                        } catch (e: java.lang.Exception) {
                            e.printStackTrace()
                            false
                        }
                    }

                // Subscribe to specified cache events on all nodes that have cache running.
                manager.igniteInstance.let {
                    val group = it.cluster().forCacheNodes(cache?.name)
                    it.events(group).remoteListen<CacheEvent>(
                        localListener, remoteFilter,
                        EventType.EVT_CACHE_OBJECT_PUT
                    )
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun commonCacheTest(clusterManager: ClusterManager) {
        // Clustered Map
        val map: MutableMap<String, String> = clusterManager.getSyncMap("mapName") // shared distributed map
        map["test"] = "test"
    }
}