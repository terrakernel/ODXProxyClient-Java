package io.odxproxy.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@Serializable(with = OdxMany2OneSerializer::class)
public data class OdxMany2One(
    public val id: Int?,
    public val name: String?
) {
    public fun isSet(): Boolean = id != null
}

@Serializable(with = OdxVariantSerializer::class)
public data class OdxVariant<T>(
    public val value: T?
)

public object OdxMany2OneSerializer : KSerializer<OdxMany2One> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("OdxMany2One")

    override fun deserialize(decoder: Decoder): OdxMany2One {
        val input = decoder as? JsonDecoder
            ?: throw IllegalStateException("This serializer can only be used with JSON")

        val element = input.decodeJsonElement()

        return when (element) {
            is JsonNull -> OdxMany2One(null, null)
            is JsonPrimitive -> OdxMany2One(null, null)
            is JsonArray -> {
                if (element.isEmpty()) {
                    OdxMany2One(null, null)
                } else {
                    val id = element.getOrNull(0)?.jsonPrimitive?.intOrNull
                    val nameElement = element.getOrNull(1)
                    val name = if (nameElement is JsonPrimitive && nameElement.isString) {
                        nameElement.content
                    } else {
                        null
                    }
                    OdxMany2One(id, name)
                }
            }
            else -> OdxMany2One(null, null)
        }
    }

    override fun serialize(encoder: Encoder, value: OdxMany2One) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw IllegalStateException("This serializer can only be used with JSON")

        if (value.id == null) {
            jsonEncoder.encodeJsonElement(JsonNull)
        } else {
            val array = buildJsonArray {
                add(value.id)
                add(value.name)
            }
            jsonEncoder.encodeJsonElement(array)
        }
    }
}

public class OdxVariantSerializer<T>(private val dataSerializer: KSerializer<T>) : KSerializer<OdxVariant<T>> {
    override val descriptor: SerialDescriptor = dataSerializer.descriptor

    override fun deserialize(decoder: Decoder): OdxVariant<T> {
        val input = decoder as? JsonDecoder
            ?: throw IllegalStateException("This serializer can only be used with JSON")

        val element = input.decodeJsonElement()

        if (element is JsonPrimitive && !element.isString && element.booleanOrNull == false) {
            return OdxVariant(null)
        }
        if (element is JsonNull) {
            return OdxVariant(null)
        }
        return try {
            val value = input.json.decodeFromJsonElement(dataSerializer, element)
            OdxVariant(value)
        } catch (e: Exception) {
            OdxVariant(null)
        }
    }

    override fun serialize(encoder: Encoder, value: OdxVariant<T>) {
        if (value.value == null) {
            encoder.encodeNull()
        } else {
            dataSerializer.serialize(encoder, value.value)
        }
    }
}