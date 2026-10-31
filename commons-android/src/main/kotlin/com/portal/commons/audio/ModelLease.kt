package com.portal.commons.audio

/**
 * One [VoskModel] shared between its owner, the wake decoder, and borrowers on other threads (the hybrid wake
 * detector's window check), so a borrower never decodes on a model its owner has already freed.
 *
 * The owner uses it as the model itself and closes it as usual. A [borrow] after that close is refused, and a borrow
 * in flight when it happens frees the native model as it ends. Exactly one of the two frees it.
 */
internal class ModelLease(private val model: VoskModel) : VoskModel {

    private var ownerClosed = false
    private var borrows = 0

    override fun newDecoder(grammar: String?): VoskDecoder = model.newDecoder(grammar)

    /** Run [block] on the model from any thread; null without running it once the owner has closed the model. */
    fun <T> borrow(block: (VoskModel) -> T): T? {
        synchronized(this) {
            if (ownerClosed) return null
            borrows++
        }
        try {
            return block(model)
        } finally {
            val free = synchronized(this) {
                borrows--
                ownerClosed && borrows == 0
            }
            if (free) model.close()
        }
    }

    /** The owner's close: frees the model now, or as the last borrow in flight ends. Idempotent. */
    override fun close() {
        val free = synchronized(this) {
            if (ownerClosed) return
            ownerClosed = true
            borrows == 0
        }
        if (free) model.close()
    }
}
