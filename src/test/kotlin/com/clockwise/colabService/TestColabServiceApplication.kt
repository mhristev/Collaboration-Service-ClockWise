package com.clockwise.colabService

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<ColabServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}
