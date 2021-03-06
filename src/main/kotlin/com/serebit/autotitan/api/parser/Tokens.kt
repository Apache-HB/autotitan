package com.serebit.autotitan.api.parser

import com.serebit.autotitan.config

data class Token(val type: TokenType, val value: String)

enum class TokenType(val regex: Regex) {
    INVOCATION("${Regex.escape(config.prefix)}\\w+".toRegex()),
    INTEGER("-?\\d+".toRegex()),
    FLOAT("-?\\d+\\.\\d+(?:E-?\\d+)?".toRegex()),
    STRING("\".+?\"".toRegex(RegexOption.DOT_MATCHES_ALL)),
    USER("<@!?\\d+?>".toRegex()),
    MEMBER("<@!?\\d+?>".toRegex()),
    CHANNEL("<#\\d+?>".toRegex());
}