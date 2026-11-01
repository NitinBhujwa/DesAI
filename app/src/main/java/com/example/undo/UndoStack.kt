package com.example.undo

class UndoStack<T>(private val maxEntries: Int = 50) {
  init {
    require(maxEntries >= 1) { "maxEntries must be at least 1" }
  }

  private val past = ArrayDeque<T>()
  private val future = ArrayDeque<T>()

  val canUndo: Boolean
    get() = past.isNotEmpty()

  val canRedo: Boolean
    get() = future.isNotEmpty()

  val undoCount: Int
    get() = past.size

  val redoCount: Int
    get() = future.size

  /**
   * Records the [previousState] before a new state becomes active, clearing the redo stack.
   * Caps history at [maxEntries].
   */
  fun push(previousState: T) {
    if (past.size >= maxEntries) {
      past.removeFirst()
    }
    past.addLast(previousState)
    future.clear()
  }

  /**
   * Pops the most recent snapshot from the undo stack and pushes [currentState] onto the redo stack.
   * Returns the restored snapshot, or null if [canUndo] is false.
   */
  fun undo(currentState: T): T? {
    if (past.isEmpty()) return null
    val previous = past.removeLast()
    if (future.size >= maxEntries) {
      future.removeFirst()
    }
    future.addLast(currentState)
    return previous
  }

  /**
   * Pops the next snapshot from the redo stack and pushes [currentState] back onto the undo stack.
   * Returns the restored snapshot, or null if [canRedo] is false.
   */
  fun redo(currentState: T): T? {
    if (future.isEmpty()) return null
    val next = future.removeLast()
    if (past.size >= maxEntries) {
      past.removeFirst()
    }
    past.addLast(currentState)
    return next
  }

  fun clear() {
    past.clear()
    future.clear()
  }
}
