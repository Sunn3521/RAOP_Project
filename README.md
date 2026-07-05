# RAOP Project

A simple Android chat application project with Firebase-backed chat features and file handling.

## Features
- Chat activity for sending and receiving messages
- File selection and upload support
- Firebase integration for storage and messaging workflows
- Gradle-based Android project structure

## Project Structure
- app/ - Android application module
- build.gradle.kts - Root Gradle build configuration
- settings.gradle.kts - Project settings
- firebase.json - Firebase configuration
- storage.rules - Firebase Storage rules

## Requirements
- Android Studio
- JDK 17 or newer
- Android SDK
- Firebase project configured

## Setup
1. Clone the repository
2. Open the project in Android Studio
3. Sync Gradle files
4. Configure your local Android SDK path in local.properties
5. Connect the app to your Firebase project

## Build
Run:

```bash
./gradlew assembleDebug
```

## Notes
- The repository includes a Gradle wrapper so you can build without installing Gradle globally.
- Local SDK configuration is usually stored in local.properties and should not be committed.
