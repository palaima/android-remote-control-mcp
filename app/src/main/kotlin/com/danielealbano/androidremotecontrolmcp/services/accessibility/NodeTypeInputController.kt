package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.tools.findFocusedEditableNode
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuInputInjector
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * [TypeInputController] for Android 11 and 12, where an accessibility service cannot act as an input method
 * (FLAG_INPUT_METHOD_EDITOR is Android 13+). It works on the input-focused editable node instead:
 * - reads come from the node text and its selection;
 * - edits are made with ACTION_SET_TEXT and ACTION_SET_SELECTION;
 * - when the node does not accept ACTION_SET_TEXT, text and key events go through [ShizukuInputInjector] (`input`
 *   run through Shizuku), if Shizuku is ready and elevated commands are enabled.
 *
 * The node reports the whole field, so every read has offset 0 and covers the field.
 *
 * **Threading**: [ShizukuInputInjector] calls are bridged with [runBlocking]; the type tools call this controller
 * from a background dispatcher, never from the main thread.
 */
@Suppress("ReturnCount")
class NodeTypeInputController
    @Inject
    constructor(
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val shizukuInputInjector: ShizukuInputInjector,
    ) : TypeInputController {
        private class Field(
            val node: AccessibilityNodeInfo,
            val text: String,
            val selectionStart: Int,
            val selectionEnd: Int,
        )

        private fun focusedField(): Field? {
            val node =
                try {
                    findFocusedEditableNode(accessibilityServiceProvider)
                } catch (_: McpToolException.PermissionDenied) {
                    null
                } ?: return null
            val text = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
            val start = node.textSelectionStart.takeIf { it in 0..text.length } ?: text.length
            val end = node.textSelectionEnd.takeIf { it in start..text.length } ?: start
            return Field(node, text, start, end)
        }

        override fun isReady(): Boolean = focusedField() != null

        override fun commitText(
            text: CharSequence,
            newCursorPosition: Int,
        ): Boolean {
            val field = focusedField() ?: return false
            val updated =
                field.text.substring(0, field.selectionStart) + text + field.text.substring(field.selectionEnd)
            val cursor = field.selectionStart + text.length
            if (setText(field.node, updated, cursor)) return true
            return runBlocking {
                when {
                    text.isNotEmpty() -> {
                        shizukuInputInjector.typeText(text.toString())
                    }

                    field.selectionEnd > field.selectionStart -> {
                        shizukuInputInjector.pressKey(KeyEvent.KEYCODE_DEL)
                    }

                    else -> {
                        true
                    }
                }
            }
        }

        override fun setSelection(
            start: Int,
            end: Int,
        ): Boolean {
            val field = focusedField() ?: return false
            // A TextView offers ACTION_SET_SELECTION only when it holds text;
            // an empty field already has its cursor at 0
            if (field.text.isEmpty() && start == 0 && end == 0) return true
            return select(field.node, start, end)
        }

        override fun getSurroundingText(
            beforeLength: Int,
            afterLength: Int,
            flags: Int,
        ): TextWindow? {
            val field = focusedField() ?: return null
            val windowStart = (field.selectionStart - beforeLength).coerceAtLeast(0)
            val windowEnd = (field.selectionEnd + afterLength).coerceAtMost(field.text.length)
            return TextWindow(
                text = field.text.substring(windowStart, windowEnd),
                selectionStart = field.selectionStart - windowStart,
                selectionEnd = field.selectionEnd - windowStart,
                offset = windowStart,
            )
        }

        override fun performContextMenuAction(id: Int): Boolean {
            val field = focusedField() ?: return false
            return when (id) {
                android.R.id.selectAll -> select(field.node, 0, field.text.length)
                android.R.id.copy -> field.node.performAction(AccessibilityNodeInfo.ACTION_COPY)
                android.R.id.cut -> field.node.performAction(AccessibilityNodeInfo.ACTION_CUT)
                android.R.id.paste -> field.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                else -> false
            }
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (focusedField() == null) return false
            if (event.action != KeyEvent.ACTION_DOWN) return true
            return runBlocking { shizukuInputInjector.pressKey(event.keyCode) }
        }

        override fun deleteSurroundingText(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean {
            val field = focusedField() ?: return false
            val deleteStart = (field.selectionStart - beforeLength).coerceAtLeast(0)
            val deleteEnd = (field.selectionEnd + afterLength).coerceAtMost(field.text.length)
            val updated =
                field.text.substring(0, deleteStart) +
                    field.text.substring(field.selectionStart, field.selectionEnd) +
                    field.text.substring(deleteEnd)
            return setText(field.node, updated, deleteStart)
        }

        override fun isPasswordInput(): Boolean = focusedField()?.node?.isPassword == true

        /** Sets the node text with ACTION_SET_TEXT and puts the cursor at [cursor]; false when the node refuses it. */
        private fun setText(
            node: AccessibilityNodeInfo,
            text: String,
            cursor: Int,
        ): Boolean {
            if (AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT !in node.actionList) return false
            val args =
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
            // The node keeps its old text until it is read again; the cursor goes where the edit ended
            node.refresh()
            select(node, cursor, cursor)
            return true
        }

        private fun select(
            node: AccessibilityNodeInfo,
            start: Int,
            end: Int,
        ): Boolean {
            // TextView refuses ACTION_SET_SELECTION when the selection would not change
            if (node.textSelectionStart == start && node.textSelectionEnd == end) return true
            val args =
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
                }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        }
    }
