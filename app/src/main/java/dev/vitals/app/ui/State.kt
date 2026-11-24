package dev.vitals.app.ui

enum class Transport { Idle, Sse, Polling }

enum class OnboardStep { Intro, Scanning, Found, Pairing, Done, Error }

enum class Sheet { None, Endpoints, Settings }
