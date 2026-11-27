package com.example.nightmareai.data.local.entity

/**
 * Defines strict isolation boundaries for chat threads.
 * - GLOBAL_ABOUT_YOU: The single cross-audit thread per googleId allowed to query all MASTER & NORMAL data.
 * - MASTER: Isolated judge workspace strictly scoped to its own threadId and googleId.
 * - NORMAL: Isolated standard audit chat strictly scoped to its own threadId and googleId.
 */
enum class ChatType {
    GLOBAL_ABOUT_YOU,
    MASTER,
    NORMAL
}
