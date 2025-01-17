package net.earthcomputer.multiconnect.ap

import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.element.VariableElement
import javax.lang.model.type.ArrayType
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.MirroredTypeException
import javax.lang.model.type.TypeKind
import javax.lang.model.type.TypeMirror
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.reflect.KClass

const val JAVA_LANG_OBJECT = "java.lang.Object"
const val JAVA_LANG_STRING = "java.lang.String"
const val JAVA_LANG_RUNTIME_EXCEPTION = "java.lang.RuntimeException"
const val JAVA_LANG_ERROR = "java.lang.Error"
const val JAVA_UTIL_BITSET = "java.util.BitSet"
const val JAVA_UTIL_LIST = "java.util.List"
const val JAVA_UTIL_OPTIONAL = "java.util.Optional"
const val JAVA_UTIL_OPTIONAL_INT = "java.util.OptionalInt"
const val JAVA_UTIL_OPTIONAL_LONG = "java.util.OptionalLong"
const val JAVA_UTIL_UUID = "java.util.UUID"
const val JAVA_UTIL_FUNCTION_CONSUMER = "java.util.function.Consumer"
const val JAVA_UTIL_FUNCTION_FUNCTION = "java.util.function.Function"
const val JAVA_UTIL_FUNCTION_SUPPLIER = "java.util.function.Supplier"
const val FASTUTIL_INT_LIST = "it.unimi.dsi.fastutil.ints.IntList"
const val FASTUTIL_LONG_LIST = "it.unimi.dsi.fastutil.longs.LongList"
const val MINECRAFT_NBT_COMPOUND = "net.minecraft.nbt.NbtCompound"
const val MINECRAFT_IDENTIFIER = "net.minecraft.util.Identifier"
const val MINECRAFT_NETWORK_HANDLER = "net.minecraft.client.network.ClientPlayNetworkHandler"
const val MULTICONNECT_TYPED_MAP = "net.earthcomputer.multiconnect.protocols.generic.TypedMap"
const val MULTICONNECT_DELAYED_PACKET_SENDER = "net.earthcomputer.multiconnect.impl.DelayedPacketSender"

val messageName: String = Message::class.java.canonicalName
val messageVariantName: String = MessageVariant::class.java.canonicalName
val polymorphicName: String = Polymorphic::class.java.canonicalName
val networkEnumName: String = NetworkEnum::class.java.canonicalName

fun count(vararg values: Boolean): Int {
    return values.count { it }
}

fun <T: Annotation> Element.getAnnotation(clazz: KClass<T>): T? {
    return getAnnotation(clazz.java)
}

fun Element.hasAnnotation(clazz: KClass<out Annotation>): Boolean {
    return getAnnotation(clazz) != null
}

fun Element.hasModifier(modifier: Modifier): Boolean {
    return modifiers.contains(modifier)
}

val Element.isEnum: Boolean
    get() = hasAnnotation(NetworkEnum::class)

val Element.isMessage: Boolean
    get() = hasAnnotation(Message::class)

val Element.isMessageVariant: Boolean
    get() = hasAnnotation(MessageVariant::class)

val Element.isPolymorphicRoot: Boolean
    get() {
        if (this !is TypeElement) return false
        if (!this.hasAnnotation(Polymorphic::class)) return false
        if (!this.hasModifier(Modifier.ABSTRACT)) return false
        return superclass.hasQualifiedName(JAVA_LANG_OBJECT)
    }

val Element.polymorphicParent: TypeElement?
    get() = (this as? TypeElement)?.superclass?.asTypeElement()?.takeIf { !it.qualifiedName.contentEquals(JAVA_LANG_OBJECT) }

val TypeElement.recordFields: List<VariableElement>
    get() = enclosedElements.mapNotNull { elt ->
        if (!elt.kind.isField) return@mapNotNull null
        if (elt.hasModifier(Modifier.STATIC)) return@mapNotNull null
        elt as VariableElement
    }

val TypeElement.allRecordFields: List<VariableElement>
    get() {
        val polymorphicParent = polymorphicParent
        return if (polymorphicParent != null) {
            polymorphicParent.recordFields + recordFields
        } else {
            recordFields
        }
    }

val TypeElement.enumConstants: List<VariableElement>
    get() = enclosedElements.mapNotNull { elt ->
        if (elt.kind != ElementKind.ENUM_CONSTANT) return@mapNotNull null
        elt as VariableElement
    }

val TypeElement.handler: ExecutableElement?
    get() {
        if (!isMessageVariant) return null
        return (enclosedElements.singleOrNull { it.hasAnnotation(Handler::class) } as? ExecutableElement)?.takeIf { it.kind == ElementKind.METHOD }
}

fun TypeElement.getPartialHandlers(
    processingEnv: ProcessingEnvironment,
    errorConsumer: ErrorConsumer? = null,
    errorElement: Element? = null
): List<MulticonnectFunction> {
    if (!isMessageVariant) return emptyList()
    return enclosedElements
        .filter { it.hasAnnotation(PartialHandler::class) && it.kind == ElementKind.METHOD }
        .mapNotNull { it as? ExecutableElement }
        .mapNotNull { findMulticonnectFunction(processingEnv, it.simpleName.toString(), errorConsumer = errorConsumer, errorElement = errorElement) }
}

fun TypeElement.isConstructable(processingEnv: ProcessingEnvironment): Boolean {
    if (kind != ElementKind.CLASS) return false
    if (hasModifier(Modifier.ABSTRACT)) return false
    if (enclosingElement.kind.isClass || enclosingElement.kind.isInterface) {
        if (!hasModifier(Modifier.STATIC)) return false
    }
    val constructors = enclosedElements.mapNotNull { ctor -> (ctor as? ExecutableElement)?.takeIf { it.kind == ElementKind.CONSTRUCTOR } }
    if (constructors.isEmpty()) return true
    val noArg = constructors.firstOrNull { it.parameters.isEmpty() } ?: return false
    if (!noArg.hasModifier(Modifier.PUBLIC)) return false
    return noArg.isThrowSafe(processingEnv)
}

fun TypeElement.findMulticonnectFunction(
    processingEnv: ProcessingEnvironment,
    name: String,
    argumentResolveContext: TypeElement? = this,
    errorConsumer: ErrorConsumer? = null,
    errorElement: Element? = null
): MulticonnectFunction? {
    val matchingMethods = enclosedElements.filter {
        it.kind == ElementKind.METHOD && it.simpleName.contentEquals(name)
    }
    if (matchingMethods.isEmpty()) {
        errorConsumer?.report("No methods named \"name\" were found in type $simpleName", errorElement!!)
        return null
    }
    if (matchingMethods.size > 1) {
        errorConsumer?.report("Multiconnect methods cannot have overloads", errorElement!!)
        return null
    }
    val method = matchingMethods.single() as ExecutableElement
    if (!method.hasModifier(Modifier.STATIC)) {
        errorConsumer?.report("Multiconnect method must be static", method)
        return null
    }
    if (!method.hasModifier(Modifier.PUBLIC)) {
        errorConsumer?.report("Multiconnect method must be public", method)
        return null
    }
    if (!method.isThrowSafe(processingEnv)) {
        errorConsumer?.report("Multiconnect methods must be throw safe", method)
        return null
    }
    val positionalParameters = mutableListOf<TypeMirror>()
    val parameters = mutableListOf<MulticonnectParameter>()
    for (parameter in method.parameters) {
        val argument = parameter.getAnnotation(Argument::class)
        val isDefaultConstruct = parameter.hasAnnotation(DefaultConstruct::class)
        val filledArgument = parameter.getAnnotation(FilledArgument::class)
        val isGlobalData = parameter.hasAnnotation(GlobalData::class)
        val paramType = parameter.asType()
        when (count(argument != null, isDefaultConstruct, filledArgument != null, isGlobalData)) {
            0 -> {
                if (parameters.isEmpty()) {
                    positionalParameters += paramType
                } else {
                    errorConsumer?.report("Positional parameter detected after non-positional parameter", parameter)
                    return null
                }
            }
            1 -> {
                when {
                    argument != null -> {
                        if (argumentResolveContext != null
                            && !argument.value.startsWith("outer.")
                            && argument.value != "this"
                            && !argumentResolveContext.allRecordFields.any { it.simpleName.contentEquals(argument.value) }
                        ) {
                            errorConsumer?.report("Could not resolve argument \"${argument.value}\"", parameter)
                            return null
                        }
                        parameters += MulticonnectParameter.Argument(paramType, argument.value, argument.translate)
                    }
                    isDefaultConstruct -> {
                        if (paramType.hasQualifiedName(JAVA_UTIL_FUNCTION_SUPPLIER)) {
                            val typeArgument = paramType.typeArguments?.singleOrNull()
                            if (typeArgument == null) {
                                errorConsumer?.report("Default construct supplier must have a type argument", parameter)
                                return null
                            }
                            if (!MulticonnectType.isSupportedType(processingEnv, typeArgument)) {
                                errorConsumer?.report("Cannot default-construct non-multiconnect type", parameter)
                                return null
                            }
                            parameters += MulticonnectParameter.SuppliedDefaultConstructed(paramType, typeArgument)
                        } else {
                            if (!MulticonnectType.isSupportedType(processingEnv, paramType)) {
                                errorConsumer?.report("Cannot default-construct non-multiconnect type", parameter)
                                return null
                            }
                            parameters += MulticonnectParameter.DefaultConstructed(paramType)
                        }
                    }
                    filledArgument != null -> {
                        val fromRegistry = filledArgument.fromRegistry.takeIf { it.value.isNotEmpty() }
                        val fromVersion = filledArgument.fromVersion.takeIf { it != -1 }
                        val toVersion = filledArgument.toVersion.takeIf { it != -1 }
                        if (fromRegistry != null) {
                            if (fromVersion != null || toVersion != null) {
                                errorConsumer?.report("Cannot specify fromRegistry and fromVersion or toVersion", parameter)
                                return null
                            }
                            if (!MulticonnectType.isRegistryCompatible(paramType)) {
                                errorConsumer?.report("Cannot fill non-registry type from a registry", parameter)
                                return null
                            }
                        } else if (fromVersion != null || toVersion != null) {
                            if (fromVersion == null || toVersion == null) {
                                errorConsumer?.report("Cannot specify fromVersion without toVersion or vice versa", parameter)
                                return null
                            }
                            var validType = false
                            if (paramType.hasQualifiedName(JAVA_UTIL_FUNCTION_FUNCTION)) {
                                val typeArguments = paramType.typeArguments
                                if (typeArguments.size == 2) {
                                    val type1 = typeArguments[0]
                                    val type2 = typeArguments[1]
                                    if (type1.isMessageVariant && type2.isMessageVariant) {
                                        if (processingEnv.typeUtils.isSameType(type1, type2)) {
                                            validType = true
                                        } else {
                                            val group1 = type1.asTypeElement()?.interfaces?.singleOrNull()
                                            val group2 = type2.asTypeElement()?.interfaces?.singleOrNull()
                                            if (group1 != null && group2 != null && processingEnv.typeUtils.isSameType(group1, group2)) {
                                                validType = true
                                            }
                                        }
                                    }
                                }
                            }
                            if (!validType) {
                                errorConsumer?.report("Invalid filled variant conversion type", parameter)
                                return null
                            }
                        } else {
                            if (!MulticonnectType.canAutoFill(paramType)) {
                                errorConsumer?.report("Cannot fill type $paramType", parameter)
                                return null
                            }
                        }
                        parameters += MulticonnectParameter.Filled(paramType, fromRegistry, fromVersion, toVersion)
                    }
                    isGlobalData -> {
                        val isValidType = if (paramType.hasQualifiedName(JAVA_UTIL_FUNCTION_CONSUMER)) {
                            val consumedType = paramType.typeArguments.singleOrNull()
                            consumedType is DeclaredType
                        } else {
                            paramType is DeclaredType
                        }
                        if (!isValidType) {
                            errorConsumer?.report("Invalid global type $paramType", parameter)
                            return null
                        }
                        parameters += MulticonnectParameter.GlobalData(paramType)
                    }
                }
            }
            else -> {
                errorConsumer?.report("Only one multiconnect parameter annotation is allowed", parameter)
                return null
            }
        }
    }

    val possibleReturnTypes = method.getAnnotationsByType(ReturnType::class.java).mapTo(mutableSetOf()) {
        toTypeMirror { it.value }
    }
    if (possibleReturnTypes.isNotEmpty()) {
        if (!method.returnType.hasQualifiedName(JAVA_UTIL_LIST)
            || !method.returnType.componentType(processingEnv).hasQualifiedName(JAVA_LANG_OBJECT)
        ) {
            errorConsumer?.report("@ReturnType is only allowed on methods that return List<Object>", method)
            return null
        }
    }

    return MulticonnectFunction(name, method.returnType, positionalParameters, parameters, possibleReturnTypes.toList())
}

inline fun toTypeMirror(func: () -> KClass<*>): TypeMirror {
    try {
        func().java
    } catch (e: MirroredTypeException) {
        return e.typeMirror
    }
    throw IllegalArgumentException("The lambda must attempt to get a class value from an annotation")
}

@OptIn(ExperimentalContracts::class)
fun TypeMirror.asTypeElement(): TypeElement? {
    contract {
        returnsNotNull() implies (this@asTypeElement is DeclaredType)
    }
    return (this as? DeclaredType)?.asElement() as? TypeElement
}

@OptIn(ExperimentalContracts::class)
fun TypeMirror.hasQualifiedName(name: String): Boolean {
    contract {
        returns(true) implies (this@hasQualifiedName is DeclaredType)
    }
    return this.asTypeElement()?.qualifiedName?.contentEquals(name) ?: false
}

val TypeMirror.isIntegral: Boolean
    get() = when(kind) {
        TypeKind.BOOLEAN, TypeKind.BYTE, TypeKind.CHAR, TypeKind.SHORT, TypeKind.INT, TypeKind.LONG -> true
        else -> false
    }
val TypeMirror.isFloatingPoint: Boolean
    get() = kind == TypeKind.FLOAT || kind == TypeKind.DOUBLE

val TypeMirror.isEnum: Boolean
    get() = asTypeElement()?.isEnum == true

val TypeMirror.isMessage: Boolean
    get() = asTypeElement()?.isMessage == true

val TypeMirror.isMessageVariant: Boolean
    get() = asTypeElement()?.isMessageVariant == true

val TypeMirror.hasLength: Boolean
    get() {
        if (this is ArrayType) return true
        return when (this.asTypeElement()?.qualifiedName?.toString()) {
            JAVA_UTIL_LIST -> ((this as DeclaredType).typeArguments?.size ?: 0) > 0
            FASTUTIL_INT_LIST, FASTUTIL_LONG_LIST -> true
            else -> false
        }
    }

val TypeMirror.isContainerType: Boolean
    get() {
        if (hasLength) return true
        return when (this.asTypeElement()?.qualifiedName?.toString()) {
            JAVA_UTIL_OPTIONAL -> ((this as DeclaredType).typeArguments?.size ?: 0) > 0
            JAVA_UTIL_OPTIONAL_INT, JAVA_UTIL_OPTIONAL_LONG -> true
            else -> false
        }
    }

fun TypeMirror.componentType(processingEnv: ProcessingEnvironment): TypeMirror {
    if (this is ArrayType) {
        return this.componentType
    }
    return when (this.asTypeElement()?.qualifiedName?.toString()) {
        JAVA_UTIL_LIST, JAVA_UTIL_OPTIONAL -> ((this as DeclaredType).typeArguments.first())
        JAVA_UTIL_OPTIONAL_INT, FASTUTIL_INT_LIST -> processingEnv.typeUtils.getPrimitiveType(TypeKind.INT)
        JAVA_UTIL_OPTIONAL_LONG, FASTUTIL_LONG_LIST -> processingEnv.typeUtils.getPrimitiveType(TypeKind.LONG)
        else -> throw IllegalStateException("Type is not a container type")
    }
}

fun TypeMirror.deepComponentType(processingEnv: ProcessingEnvironment): TypeMirror {
    var type = this
    while (type.isContainerType) {
        type = type.componentType(processingEnv)
    }
    return type
}

fun VariableElement.getMulticonnectType(processingEnv: ProcessingEnvironment): MulticonnectType? {
    val realType = asType()
    val deepComponentType = realType.deepComponentType(processingEnv)
    val wireType = getAnnotation(Type::class)?.value ?: MulticonnectType.defaultWireType(deepComponentType) ?: return null
    val registry = getAnnotation(Registry::class)?.value
    val lengthInfo = getAnnotation(Length::class)
    val defaultConstructInfo = getAnnotation(DefaultConstruct::class)
    val onlyIf = getAnnotation(OnlyIf::class)?.value
    val datafixInfo = getAnnotation(Datafix::class)
    val polymorphicBy = getAnnotation(PolymorphicBy::class)?.field
    val introduce = getAnnotationsByType(Introduce::class.java).toList()
    val customFix = getAnnotationsByType(CustomFix::class.java).toList()
    return MulticonnectType(realType, wireType, registry, lengthInfo, defaultConstructInfo, onlyIf, datafixInfo, polymorphicBy, introduce, customFix)
}

fun ExecutableElement.isThrowSafe(processingEnv: ProcessingEnvironment, vararg allowedExceptions: TypeMirror): Boolean {
    val runtimeException = processingEnv.elementUtils.getTypeElement(JAVA_LANG_RUNTIME_EXCEPTION)?.asType() ?: return true
    val error = processingEnv.elementUtils.getTypeElement(JAVA_LANG_ERROR)?.asType() ?: return true
    return thrownTypes.all { thrownType ->
        processingEnv.typeUtils.isSubtype(thrownType, runtimeException)
                || processingEnv.typeUtils.isSubtype(thrownType, error)
                || allowedExceptions.any { processingEnv.typeUtils.isSubtype(thrownType, it) }
    }
}
