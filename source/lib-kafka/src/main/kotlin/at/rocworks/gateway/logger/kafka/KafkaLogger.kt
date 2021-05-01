package at.rocworks.gateway.logger.kafka

import at.rocworks.gateway.core.logger.LoggerBase

import io.vertx.core.json.JsonObject
import io.vertx.kafka.client.producer.KafkaProducer
import java.util.concurrent.TimeUnit
import io.vertx.kafka.client.producer.KafkaProducerRecord

class KafkaLogger(config: JsonObject) : LoggerBase(config) {
    private val servers = config.getString("Servers", "localhost:9092")

    private var producer: KafkaProducer<String, String>? = null

    override fun open(): Boolean {
        return try {
            val config: MutableMap<String, String> = HashMap()
            config["bootstrap.servers"] = servers
            config["key.serializer"] = "org.apache.kafka.common.serialization.StringSerializer"
            config["value.serializer"] = "org.apache.kafka.common.serialization.StringSerializer"
            config["acks"] = "1"
            producer = KafkaProducer.create(vertx, config)
            logger.info("Kafka connected.")
            true
        } catch (e: Exception) {
            logger.error("Kafka connect failed! [{}]", e.message)
            e.printStackTrace()
            false
        }
    }

    override fun close() {
        producer?.close()
    }

    override fun writeExecutor() {
        var counter = 0
        var point: DataPoint? = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
        while (point != null) {
            try {
                val time = point.value.sourceTime().toEpochMilli()
                val value = point.value.valueAsDouble() ?: point.value.valueAsString()
                val status = point.value.statusAsString()

                val data = JsonObject()
                    .put("time", time)
                    .put("value", value)
                    .put("status", status)

                val record: KafkaProducerRecord<String, String> = KafkaProducerRecord.create(
                    point.topic.systemName,
                    point.topic.browsePath,
                    data.toString()
                )
                if (producer?.writeQueueFull()==true) {
                    logger.warn("Kafka write queue full!")
                    while (producer?.writeQueueFull()==true) {
                        Thread.sleep(100)
                    }
                    logger.warn("Kafka write queue not full anymore.")
                }
                producer?.write(record)?.onComplete {
                    valueCounterOutput++
                }

            } catch (e: Exception) {
                logger.error(e.message)
            }
            point = if (++counter < writeParameterBlockSize) writeValueQueue.poll() else null
        }
    }

    override fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeNano: Long,
        toTimeNano: Long,
        result: (Boolean, List<List<Any>>?) -> Unit
    ) {
        result(false, null)
    }
}