package software.amazon.ionschema.internal.constraint

import software.amazon.ion.IonList
import software.amazon.ion.IonSymbol
import software.amazon.ion.IonValue
import software.amazon.ionschema.InvalidSchemaException
import software.amazon.ionschema.Violations
import software.amazon.ionschema.Violation
import software.amazon.ionschema.internal.ConstraintInternal
import software.amazon.ionschema.internal.util.withoutTypeAnnotations

/**
 * Implements the annotations constraint.
 *
 * Invocations are delegated to either an Ordered or Unordered implementation.
 *
 * @see https://amzn.github.io/ion-schema/docs/spec.html#annotations
 */
internal class Annotations private constructor(
        ion: IonValue,
        private val delegate: ConstraintInternal
) : ConstraintBase(ion), ConstraintInternal by delegate {

    constructor(ion: IonValue) : this(ion, delegate(ion))

    companion object {
        private fun delegate(ion: IonValue): ConstraintInternal {
            val requiredByDefault = ion.hasTypeAnnotation("required")
            if (ion !is IonList || ion.isNullValue) {
                throw InvalidSchemaException("Expected annotations as a list, found: $ion")
            }
            val annotations = ion.map {
                Annotation(it as IonSymbol, requiredByDefault)
            }
            return if (ion.hasTypeAnnotation("ordered")) {
                    OrderedAnnotations(ion, annotations)
                } else {
                    UnorderedAnnotations(ion, annotations)
                }
        }
    }

    override val name = delegate.name
}

/**
 * Ordered implementation of the annotations constraint, backed by a [StateMachine].
 */
internal class OrderedAnnotations(
        ion: IonValue,
        private val annotations: List<Annotation>
) : ConstraintBase(ion) {

    private val ION = ion.system

    private val stateMachine: StateMachine

    init {
        val stateMachineBuilder = StateMachineBuilder()

        // support for open content at the beginning:
        stateMachineBuilder.addTransition(
                stateMachineBuilder.initialState, Event.ANY, stateMachineBuilder.initialState)

        var state = stateMachineBuilder.initialState

        (ion as IonList).forEachIndexed { idx, it ->
            val newState = State(isFinal = idx == ion.size - 1)
            val annotationSymbol = it.withoutTypeAnnotations()
            stateMachineBuilder.addTransition(state, Event(annotationSymbol), newState)
            if (!annotations[idx].isRequired) {
                // optional annotations are modeled as no-op events
                stateMachineBuilder.addTransition(state, Event.NOOP, newState)
            }

            // support for open content
            stateMachineBuilder.addTransition(newState, Event.ANY, newState)

            state = newState
        }

        stateMachine = stateMachineBuilder.build()
    }

    override fun validate(value: IonValue, issues: Violations) {
        if (!stateMachine.matches(value.typeAnnotations.map { ION.newSymbol(it) }.iterator())) {
            issues.add(Violation(ion, "annotations_mismatch", "annotations don't match expectations"))
        }
    }
}

/**
 * Unordered implementation of the annotations constraint.
 */
internal class UnorderedAnnotations(
        ion: IonValue,
        private val annotations: List<Annotation>
) : ConstraintBase(ion) {

    override fun validate(value: IonValue, issues: Violations) {
        val missingAnnotations = mutableListOf<Annotation>()
        annotations.forEach {
            if (it.isRequired && !value.hasTypeAnnotation(it.text)) {
                missingAnnotations.add(it)
            }
        }

        if (missingAnnotations.size > 0) {
            issues.add(Violation(ion, "missing_annotation",
                    "missing annotation(s): " + missingAnnotations.joinToString { it.text }))
        }
    }
}

internal class Annotation(
        ion: IonSymbol,
        requiredByDefault: Boolean
) {
    val text = ion.stringValue()

    val isRequired = if (ion.hasTypeAnnotation("required")) {
            true
        } else if (ion.hasTypeAnnotation("optional")) {
            false
        } else {
            requiredByDefault
        }
}
