package com.waarchiver

object NotificationStore {
    data class Message(val timestamp: Long, val contact: String, val text: String)

    private val messages = mutableListOf<Message>()

    fun add(timestamp: Long, contact: String, text: String) {
        messages.add(Message(timestamp, contact, text))
    }

    fun getAll(): List<Message> = messages.toList()

    fun clear() = messages.clear()
}
