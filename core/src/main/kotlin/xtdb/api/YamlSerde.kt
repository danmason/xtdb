@file:JvmName("YamlSerde")

package xtdb.api

import com.charleskorn.kaml.*
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.serializer
import xtdb.api.log.InMemoryLogFactory
import xtdb.api.log.LocalLogFactory
import xtdb.api.log.LogFactory
import xtdb.api.storage.ObjectStoreFactory
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.ServiceLoader.Provider
import kotlin.reflect.KClass

object EnvironmentVariableProvider {
    fun getEnvVariable(name: String): String? = System.getenv(name)
}

fun envFromTaggedNode(taggedNode: YamlTaggedNode ): String {
    if (taggedNode.tag == "!Env") {
        val value = taggedNode.innerNode.yamlScalar.content
        return EnvironmentVariableProvider.getEnvVariable(value) ?: throw IllegalArgumentException("Environment variable '$value' not found")
    }
    return taggedNode.innerNode.yamlScalar.content
}
fun handleEnvTag(input: YamlInput): String {
    val currentLocation = input.getCurrentLocation()
    val scalar = input.node.yamlMap.entries.values.find { it.location == currentLocation }

    return when (scalar) {
        is YamlTaggedNode -> envFromTaggedNode(scalar.yamlTaggedNode)
        is YamlScalar -> scalar.content
        else -> throw IllegalStateException()
    }
}
object PathWithEnvVarSerde : KSerializer<Path> {
    override val descriptor = PrimitiveSerialDescriptor("PathWithEnvVars", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Path) { encoder.encodeString(value.toString()) }

    override fun deserialize(decoder: Decoder): Path {
        val yamlInput: YamlInput = decoder as YamlInput
        val str = handleEnvTag(yamlInput)
        return Paths.get(str)
    }
}
object StringWithEnvVarSerde : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StringWithEnvVars", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
    override fun deserialize(decoder: Decoder): String {
        val yamlInput: YamlInput = decoder as YamlInput
        return handleEnvTag(yamlInput)
    }
}
interface ModuleRegistry {
    @OptIn(InternalSerializationApi::class)
    fun <F : Xtdb.ModuleFactory> registerModuleFactory(factory: KClass<F>, serializer: KSerializer<F> = factory.serializer())
    @OptIn(InternalSerializationApi::class)
    fun <F : LogFactory > registerLogFactory(factory: KClass<F>, serializer: KSerializer<F> = factory.serializer())
    @OptIn(InternalSerializationApi::class)
    fun <F : ObjectStoreFactory > registerObjectStore(factory: KClass<F>, serializer: KSerializer<F> = factory.serializer())
}

interface ModuleRegistration {
    fun register(registry: ModuleRegistry)
}

val YAML_SERDE = Yaml(
    serializersModule = SerializersModule {
        polymorphic(LogFactory::class) {
            subclass(InMemoryLogFactory::class)
            subclass(LocalLogFactory::class)
        }

        ServiceLoader.load(ModuleRegistration::class.java)
            .stream()
            .map(Provider<ModuleRegistration>::get)
            .forEach {
                it.register(object : ModuleRegistry {
                    override fun <F : Xtdb.ModuleFactory> registerModuleFactory(
                        factory: KClass<F>,
                        serializer: KSerializer<F>,
                    ) {
                        polymorphic(Xtdb.ModuleFactory::class) { subclass(factory, serializer) }
                    }

                    override fun <F : LogFactory> registerLogFactory(
                        factory: KClass<F>,
                        serializer: KSerializer<F>,
                    ) {
                        polymorphic(LogFactory::class) { subclass(factory, serializer) }
                    }

                    override fun <F : ObjectStoreFactory> registerObjectStore(
                        factory: KClass<F>,
                        serializer: KSerializer<F>,
                    ) {
                        polymorphic(ObjectStoreFactory::class) { subclass(factory, serializer) }
                    }
                })
            }
    })
fun nodeConfig(yamlString: String): Xtdb.Config =
    YAML_SERDE.decodeFromString<Xtdb.Config>(yamlString)

fun submitClient(yamlString: String): XtdbSubmitClient.Config =
    YAML_SERDE.decodeFromString<XtdbSubmitClient.Config>(yamlString)
