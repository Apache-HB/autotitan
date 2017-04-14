package com.serebit.autotitan.data

import com.serebit.autotitan.annotations.ListenerFunction
import java.lang.reflect.Method

class Listener(val instance: Any, val method: Method, info: ListenerFunction) {
  val name = when (info.name) {
    "" -> method.name.toLowerCase()
    else -> info.name
  }
  val description = info.description
  val eventType = info.eventType
  val serverOnly = info.serverOnly
}