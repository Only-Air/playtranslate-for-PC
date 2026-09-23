@file:Suppress("unused")

package androidx.annotation

/**
 * The `androidx.annotation` shims.
 *
 * PORTING.md §2.1 folds "only `android.util.Log` or annotations" into one
 * bucket. They are different jobs and this file is why: the annotations are
 * *compile-time markers with empty bodies*, so off-device the shim costs
 * nothing at all, whereas `Log` had to be given a real implementation. Seven
 * files need these; they are listed in `pc/PORTABILITY.md`.
 *
 * All of them are `@Retention(BINARY)` upstream, which is why declaring them
 * as ordinary annotations is not a lie: nothing reflects on them at runtime in
 * either codebase. They exist so a call site can say "this `Int` is a string
 * resource, not an arbitrary integer", and a linter can check it.
 *
 * `@StringRes` is the one that matters most here: `IconGestureBindings` hangs
 * the user-facing title of every action off it, so `Action`'s registry in
 * `core/action/` had to choose between keeping the annotation and replacing
 * the `R.string` lookup behind it. See `Action.titleKey`.
 */

@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FIELD,
    AnnotationTarget.LOCAL_VARIABLE,
)
annotation class StringRes

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class StyleRes

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class ColorRes

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class DrawableRes

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class LayoutRes

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class IdRes

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class DimenRes

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class AttrRes

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class ArrayRes

@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FIELD,
)
annotation class ColorInt

/** The `@Px` marker: "this Int is pixels". Empty off-device, as upstream. */
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FIELD,
    AnnotationTarget.LOCAL_VARIABLE,
)
annotation class Px

@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FIELD,
    AnnotationTarget.LOCAL_VARIABLE,
)
annotation class Dimension

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class VisibleForTesting

@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FIELD,
)
annotation class CheckResult

/**
 * `@Keep`'s job on Android is to stop R8 removing a member that is only
 * reached reflectively. There is no R8 here, so it is a marker and nothing
 * else — but it is kept deliberately rather than deleted, because the members
 * it guards in upstream (`PtJson`'s adapters) are still reflection-reached and
 * a future packaging step may well want to honour it.
 */
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FIELD,
    AnnotationTarget.CONSTRUCTOR,
)
annotation class Keep
