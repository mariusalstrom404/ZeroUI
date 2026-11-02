ZeroUI: Voice-Driven Interface System

ZeroUI is a lightweight, voice-first interaction system that eliminates the need for traditional graphical user interfaces. Instead of tapping, swiping, or navigating menus, users interact with devices through natural language commands, enabling a more intuitive and hands-free experience.

This project focuses on translating spoken input into executable actions on a device. Commands are understood by a cloud LLM (OpenAI), so the text of each command leaves the device and an internet connection is required. For tasks in apps without a script, on-screen text read from the accessibility tree is also sent to OpenAI (never screenshots, or what's typed in password fields). See docs/llm-setup-and-testing.md for setup.

Key Features

Natural Language Command Processing
Understands flexible, conversational user input rather than rigid command syntax.
Voice-to-Action Pipeline
Converts speech → text → intent → system action in real time.
Extensible Architecture
Modular design allows easy integration with mobile apps, IoT devices, or accessibility services.
Accessibility-Oriented
Enhances usability for users who prefer or require hands-free interaction.

Use Cases

Hands-free smartphone control (e.g., opening apps, sending messages)
Smart environment interaction (IoT devices, home automation)
Accessibility tools for users with physical limitations
Productivity enhancement through voice shortcuts
Tech Stack (Example)
Python / Java / Kotlin (platform dependent)
Speech Recognition (e.g., Vosk, Whisper)
Intent Parsing (OpenAI; local rules read follow-up answers first)
Android Accessibility APIs (for system-level control)
Project Goal

The goal of ZeroUI is to explore a future where interfaces become invisible, and interaction becomes seamless—driven entirely by intent and context rather than visual elements.
