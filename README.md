# Skip Counter — Sound-Based Rope Jump Detection

A mobile app that counts skipping rope jumps using real-time audio analysis from the device microphone. No buttons, no wearables — just the sound of the rope.

## Problem Statement

Manually counting skips while training is distracting and breaks focus on form. Wearables and counters are expensive or unavailable. This app solves that by detecting the distinctive sound of each rope impact automatically.

## How It Works

**Core Feature**: Amplitude spike detection
- The app continuously listens to microphone input
- Each rope hit produces a brief, sharp sound (distinctive frequency & amplitude)
- A calibration step at the start establishes the baseline noise threshold
- The app counts amplitude spikes above that threshold, with a cooldown to avoid double-counting

**Why This Approach**:
- Simple to implement and debug (no ML models needed)
- Works in real-time with minimal latency
- Transparent algorithm (easy to tune and explain)

## Architecture

### MVVM Pattern
UI Layer (Activities/Fragments)

↓ (observes)

ViewModel (state, LiveData)

↓ (uses)

Repository (abstract data access)

↓ (coordinates)

AudioEngine + Room Database

**Why MVVM**:
- **Separation of concerns**: UI doesn't touch audio or database APIs
- **Testability**: Each layer can be tested independently
- **Scalability**: Easy to swap AudioEngine for a different implementation (e.g., ML-based)

### Layers

| Layer | Responsibility | Key Files |
|-------|---|---|
| **UI** | Display screens, handle user input | HomeActivity, CountingActivity, ResultsFragment |
| **ViewModel** | State management, skip count logic, coordinate layers | CounterViewModel |
| **Repository** | Abstract data sources, coordinate audio & database | SkipRepository |
| **AudioEngine** | Mic input, PCM buffer processing, amplitude detection | AudioEngine.kt |
| **Database** | Persist sessions locally | Room DB, SessionDao, Session entity |

## Tech Stack

- **Language**: Kotlin
- **Architecture**: MVVM + Repository pattern
- **UI**: Jetpack (LiveData, ViewModel)
- **Audio**: Android AudioRecord API (native, low-latency)
- **Storage**: Room Database + SQLite
- **Build**: Gradle with Kotlin DSL

## Project Structure
src/main/kotlin/com/hemnaath/skipcounter/

├── ui/

│   ├── home/           # Entry screen

│   ├── counting/       # Live counter during session

│   └── results/        # Summary stats after session

├── viewmodel/

│   └── CounterViewModel.kt

├── repository/

│   └── SkipRepository.kt

├── audio/

│   └── AudioEngine.kt  # Core detection logic

├── database/

│   ├── entity/

│   │   └── Session.kt

│   ├── dao/

│   │   └── SessionDao.kt

│   └── AppDatabase.kt

└── utils/

└── Constants.kt

## Features (Phase 1)

- ✅ Real-time skip counting via sound detection
- ✅ Live timer and counter display
- ✅ Session summary (total skips, duration, skips/min)
- ✅ Local session history (Room DB)

## Future Enhancements (Phase 2+)

- Milestone notifications (every 100 skips)
- Sound sensitivity calibration UI
- Personal best tracking
- Stats dashboard (trends, graphs)
- Double-under detection (fast rope spins)
- Spring Boot backend + cloud sync

## Development Roadmap

| Phase | Focus | Timeline |
|-------|---|---|
| **1** | AudioEngine + basic MVVM | Current |
| **2** | Room DB + session history | After SQL learning |
| **3** | Spring Boot backend | After Spring Boot learning |

## Building & Running

```bash
# Clone repo
git clone https://github.com/hemnaath/skip-counter.git
cd skip-counter

# Build (Android Studio or command line)
./gradlew build

# Run on device/emulator
./gradlew installDebug
```

## Architecture Decisions

### Why AudioRecord Over Other APIs
- Direct, low-level access to PCM data
- Minimal latency (no overhead)
- Gives fine control for signal processing

### Why Room DB
- Type-safe, compile-checked SQL queries
- Lifecycle-aware
- No ORM overhead; simple queries for session storage

### Why Not Machine Learning (Initially)
- Adds complexity; limits learning
- Amplitude detection works surprisingly well for a focused problem
- ML can be added in Phase 2 if needed

## Testing Strategy

1. **Unit tests**: AudioEngine amplitude detection
2. **Integration tests**: Repository + Database
3. **Manual testing**: Actual rope skipping in different environments

## Interview Story

> *"I built this app because I skip rope for sprint training, and manually counting is distracting. The core challenge was distinguishing rope sound from ambient noise — I solved it with a calibration-based amplitude threshold plus a cooldown window to avoid false positives.*
>
> *I used MVVM to separate UI, business logic, and data sources. The AudioEngine is isolated so I can iterate on the detection algorithm independently. The Repository abstracts data access, so if I later add cloud sync or swap the audio implementation, nothing else breaks.*
>
> *It's a portfolio piece that demonstrates systems thinking, problem-solving, and clean architecture — plus it's domain-relevant to sports-tech roles I'm targeting."*

## Author

Hemnaath — SDE candidate, sports-tech focus

## License

MIT