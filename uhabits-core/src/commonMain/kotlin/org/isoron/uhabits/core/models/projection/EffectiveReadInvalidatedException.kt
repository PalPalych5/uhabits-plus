package org.isoron.uhabits.core.models.projection

/** No stable snapshot could be captured; revision listeners must request a fresh read. */
class EffectiveReadInvalidatedException : IllegalStateException(
    "Dataset/calendar changed repeatedly during snapshot creation; refresh required"
)
