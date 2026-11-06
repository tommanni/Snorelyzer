# Snorelyzer Coding Guidelines & Architectural Standards

## 1. Tech Stack Overview
- **Language**: Kotlin 2.2+ (Target & Compile SDK 36, Java 11)
- **UI Framework**: Jetpack Compose with Material 3 (`androidx.compose.material3`)
- **Navigation**: Jetpack Navigation Compose with type-safe `@Serializable` routes
- **Dependency Injection**: Koin 4.x (`io.insert-koin`) using the Kotlin DSL (no KSP annotations for Koin)
- **Local Database**: Room 2.8+ using KSP (`androidx.room.compiler`)
- **ML / Audio Engine**: Google LiteRT (`com.google.ai.edge.litert`) + JTransforms
- **Testing**: JUnit 4, Kotlin Coroutines Test (`runTest`, `advanceUntilIdle`), Compose UI Test Rule

---

## 2. Project Architecture & Package Structure
Package root: `com.example.snorelyzer`

```text
com.example.snorelyzer/
├── data/local/         # Room Database, Entities, DAOs, Data Sources & Mappers
├── di/                 # Koin Modules (e.g., AppModule.kt)
├── ml/                 # LiteRT classification models, FFT, Audio Pipelines & Buffers
├── presentation/       # Feature-centric UI packages (e.g., record/, insights/)
│   ├── [feature]/      # Feature Root, Screen, ViewModel, State, Action & Event
│   └── SnorelyzerApp.kt# Main Navigation Graph & Top-Level Scaffold
└── ui/theme/           # Color, Type, Material 3 Theme setup
```

---

## 3. UI & Presentation Layer (MVI / Unidirectional Data Flow)

### A. Root / Screen Composable Pattern
Split every screen/feature into two Composables:
1. **`[Feature]Root`**:
   - Injects the ViewModel via `koinViewModel()`.
   - Collects UI State with `collectAsStateWithLifecycle()`.
   - Listens to one-off side-effect events using `LaunchedEffect(viewModel.events)`.
   - Passes callbacks/lambdas down to `[Feature]Screen`.

2. **`[Feature]Screen`**:
   - Pure, stateless Composable UI.
   - Accepts `state: [Feature]State` and `onAction: ([Feature]Action) -> Unit`.
   - Enables easy Compose previews and UI testing without needing a ViewModel.

### B. ViewModel State Management
Follow strict MVI / UDF naming and structures within the feature package:

```kotlin
// State (Data class with defaults)
data class RecordState(
    val isServiceRunning: Boolean = false,
    val latestStatus: String = "Waiting for audio..."
)

// User Actions (Sealed interface)
sealed interface RecordAction {
    data object OnStartClick : RecordAction
    data class OnPermissionResult(val granted: Boolean) : RecordAction
}

// Side Effects / Events (Sealed interface for 1-time navigation/toast events)
sealed interface RecordEvent {
    data object RequestPermission : RecordEvent
    data object StartService : RecordEvent
}
```

- **ViewModel implementation guidelines**:
  - Expose state as `val state: StateFlow<[Feature]State> = _state.asStateFlow()`.
  - Mutate state using `_state.update { it.copy(...) }`.
  - Side effects must use `Channel<[Feature]Event>` exposed as `val events = _events.receiveAsFlow()`.
  - Handle actions in a single `fun onAction(action: [Feature]Action)` function.

### C. Design System & UI Standards (Material 3 Expressive)
All UI additions and updates must maintain a modern (2026+) Material 3 look and feel:

1. **Database-to-UI Hierarchy Rule**:
   - Always structure UI presentation to match the underlying Room database hierarchy:
     `SleepNight` (Calendar/Date grouping) $\rightarrow$ `RecordingSession` (`recording_sessions`) $\rightarrow$ `RecordingEpisode` (`recording_episodes`) $\rightarrow$ `EventGroups` (`recording_episode_groups`) & `EventSpans` (`event_spans`).
   - Do not flatten multi-tiered database records into flat lists; visually nest children inside parent containers.

2. **Semantic M3 Color Roles**:
   - Never hardcode static colors (e.g., `Color.White`, `Color.Gray`). Always use Material 3 semantic theme tokens.
   - **Surface & Container Hierarchy**:
     - Screen background: `MaterialTheme.colorScheme.background` / `surface`
     - Hero / Overview summary cards: `surfaceContainerHigh`
     - Section / Session container cards: `surfaceContainerLow`
     - Child / Episode items: `surfaceContainer` or `surfaceContainerHighest`
   - **Sound Event Semantic Palette** (apply consistently across recording & insights):
     - **Snoring (`RecordedEventGroup.Snoring`)**: `primaryContainer` / `onPrimaryContainer` (`💤`)
     - **Gasp / Snort (`RecordedEventGroup.Gasp`)**: `tertiaryContainer` / `onTertiaryContainer` (`😮`)
     - **Cough (`RecordedEventGroup.Cough`)**: `errorContainer` / `onErrorContainer` (`😷`)
     - **Sleep Talking (`RecordedEventGroup.SleepTalking`)**: `secondaryContainer` / `onSecondaryContainer` (`🗣️`)

3. **Shapes & Corner Radii**:
   - **Hero / Overview Cards**: `RoundedCornerShape(20.dp)` – `24.dp`
   - **Container / Session Cards**: `RoundedCornerShape(16.dp)`
   - **Child Items / Episodes**: `RoundedCornerShape(12.dp)`
   - **Badges, Tags & Date Selector Pills**: `RoundedCornerShape(8.dp)` – `16.dp`
   - **Action Buttons & Status Indicators**: `CircleShape`

4. **Typography & Hierarchy**:
   - Card / Section titles: `MaterialTheme.typography.titleMedium` (`FontWeight.Bold`)
   - Hero metric values: `titleMedium` / `headlineSmall` (`FontWeight.Bold`)
   - Badges & Chips: `MaterialTheme.typography.labelSmall` / `labelMedium` (`FontWeight.SemiBold`)
   - Subtext / Metadata: `MaterialTheme.typography.bodySmall` / `labelSmall` with `onSurfaceVariant` or `outline`

5. **Spacing & Padding Hygiene**:
   - Screen horizontal padding: `16.dp` (avoid excessive nested paddings that squeeze cards on smaller displays)
   - Vertical spacing between major cards: `12.dp` – `16.dp`
   - Card internal padding: `16.dp` for parent containers, `12.dp` for child items

6. **Micro-Interactions, Motion & Audio Visuals**:
   - Apply `Modifier.animateContentSize()` on expandable cards and collapsible containers.
   - Use `AnimatedVisibility` for toggling sub-elements (e.g., event spans breakdown or collapsed session episodes).
   - Active playback: Use circular tonal icon buttons (`FilledTonalIconButton`) with dynamic state (play/pause) and an animated 4-bar equalizer wave during audio playback.
   - Active tracking sessions: Feature an animated/pulsing indicator or distinct container tint.

7. **Previews & Tooling**:
   - Provide `@Preview` composables for both Light and Dark modes wrapped in `SnorelyzerTheme` for every screen and complex component.

---

## 4. Dependency Injection (Koin)
- Define modules in `com.example.snorelyzer.di` (e.g., `AppModule.kt`).
- Prefer Koin DSL helper methods (`viewModelOf`, `factoryOf`, `single`) over manual instantiation where possible:
  ```kotlin
  single<SleepRecordingLocalDataSource> { RoomSleepRecordingDataSource(dao = get()) }
  factoryOf(::AudioGate)
  viewModelOf(::InsightsViewModel)
  ```

---

## 5. Data & Database Layer (Room)
- **Entities & Relations**: Define database tables as immutable `data class` entities (`@Entity`). Group relations cleanly using embedded/relation classes (e.g., `RecordingSessionWithEpisodes`).
- **DAOs**: Interfaces annotated with `@Dao`. Use `@Transaction` for multi-query or cascade mutation operations. Return `Flow<T>` for observable queries.
- **Data Source Abstraction**: Wrap Room DAOs behind explicit Data Source interfaces (e.g., `SleepRecordingLocalDataSource`) to decouple presentation/domain code from direct Room APIs.

---

## 6. Type-Safe Navigation
- Define routes as `@Serializable` data objects or classes inside `presentation/`:
  ```kotlin
  @Serializable
  data object RecordRoute

  @Serializable
  data object InsightsRoute
  ```
- Use `navController.navigate(RouteObject)` and `composable<RouteObject> { ... }`.

---

## 7. Testing Standards
- **Unit Testing ViewModels**:
  - Use `JUnit 4` and `kotlinx.coroutines.test`.
  - Use `MainDispatcherRule` (JUnit `@Rule`) to replace `Dispatchers.Main` with a `TestDispatcher`.
  - Test asynchronous updates using `runTest` and `advanceUntilIdle()`.
  - Prefer **Fakes / Test Doubles** implementing interfaces (e.g., `FakeSleepTrackingSessionStateSource`) over using mocking frameworks like Mockito/MockK.
- **Instrumentation & UI Testing**:
  - Located in `app/src/androidTest`.
  - Use standard Jetpack Compose testing APIs (`createComposeRule()` or `createAndroidComposeRule<MainActivity>()`).
