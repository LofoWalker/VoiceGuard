# Rapport de relecture — VoiceGuard-Engine

**Date :** 2026-05-21
**Branche relue :** `epic-3-validation-harness`
**Périmètre :** moteur Kotlin (`voiceguard-engine/`), documentation (`prd.md`, `architecture.md`, `README.md`), configuration Gradle.
**État du build :** `./gradlew test` → **104 tests, 0 échec, 0 erreur**, BUILD SUCCESSFUL.

---

## 1. Synthèse

Le projet est d'une **qualité d'ingénierie remarquable sur le plan structurel** : architecture hexagonale rigoureuse, couverture de tests dense et déterministe, documentation et ADR cohérents, garde-fous numériques (NaN, ratchet de confiance) bien implémentés. La discipline de couche est même *vérifiée par des tests* (`DomainScopeComplianceTest`), ce qui est rare et excellent.

En revanche, la relecture met en évidence un **écart majeur entre l'infrastructure (solide) et la capacité de détection réelle (encore inexistante)**. Les deux règles porteuses de signal discriminant — l'analyse spectrale (R-02) et la latence comportementale (R-01) — sont en pratique **inertes dans le pipeline réel**. Conséquence directe : le banc de validation de l'Epic 3 est fonctionnel mais **ne peut aujourd'hui mesurer aucun des KPI de détection** (précision ≥ 85 %, recall) de façon significative.

S'ajoute une **régression de correction** dans le harness de production (instances de règles partagées entre fichiers) qui invalide l'isolation par fichier promise par le contrat.

| Sévérité | Nombre | Thème principal |
|----------|--------|-----------------|
| 🔴 Critique / Élevée | 3 | Détection non opérationnelle, état partagé entre fichiers |
| 🟠 Moyenne | 3 | Sémantique de confiance, faux-positifs de mesure, absence de recall |
| 🟡 Faible / Cosmétique | ~7 | Doc obsolète, duplication, ergonomie CI |

---

## 2. Points forts

- **Architecture hexagonale réellement respectée.** Le domaine (`domain/`) n'a aucune dépendance Android/TFLite ; les bindings matériels sont isolés dans `adapters/`. Cette frontière est testée à l'exécution (`DomainScopeComplianceTest` charge `android.content.Context` et vérifie son absence) — un garde-fou CI exemplaire.
- **Ports propres.** `AudioDetectionRule`, `AudioSourcePort`, `SpectralClassifierPort` sont des abstractions minimales et bien documentées. L'injection du `CoroutineDispatcher` permet des tests déterministes via `UnconfinedTestDispatcher`.
- **Garde-fous numériques corrects.** Division par zéro (ADR-04) et propagation NaN/Inf neutralisées dans `ScoreAggregator` ; ratchet monotone de `globalConfidence` (ADR-02) correctement implémenté et testé.
- **Sémantique de valeur d'`AudioChunk`** : `equals`/`hashCode` redéfinis pour `FloatArray` (`contentEquals`), avec `require()` de validation — soigné.
- **Résilience du harness** : `ValidationRunner.runValidation()` isole chaque fichier dans un `try/catch` ; un fichier corrompu n'interrompt pas le run.
- **Couverture de tests** : 104 tests couvrant warm-up, ratchet, early-exit, échantillonnage intermittent, latence, métriques. Les WAV synthétiques générés en `@TempDir` rendent les tests autoportants.
- **Posture de sécurité saine** : lecture de fichiers locaux uniquement, pas d'injection shell dans la tâche Gradle (`datasetPath` passé en argument), aucune surface réseau.

---

## 3. Constats critiques / élevés

### 🔴 C-1 — La règle spectrale R-02 est un stub : le détecteur ML n'analyse rien

`TFLiteSpectralAdapter.classify()` renvoie une constante.

```kotlin
// adapters/TFLiteSpectralAdapter.kt:54
override fun classify(chunk: AudioChunk): Float {
    // TODO Phase 2: run TFLite model with NnApiDelegate on the PCM input buffer
    return NEUTRAL_SCORE   // 0.5f
}
```

Dans le harness de production, l'adaptateur est explicitement instancié avec `hardwareAccelerationAvailable = true` (`ValidationRunner.kt:203`), ce qui **court-circuite la garde matérielle** et fait toujours retourner 0,5. R-02 (poids 0,35, le seul composant qui regarde réellement le contenu fréquentiel pour distinguer une voix synthétique) n'apporte donc **aucun pouvoir discriminant**.

C'est documenté comme « travail Phase 2 », mais cela signifie que **l'objectif central de l'Epic 3 — valider la précision de détection — ne peut pas être atteint en l'état**.

### 🔴 C-2 — La règle de latence R-01 ne s'active jamais dans le pipeline réel

`LatencyBehaviorRule` retourne `confidence = 0.0` tant qu'aucun *speech-switch* n'a été enregistré (`LatencyBehaviorRule.kt:31`). Or, dans tout le code de production, **rien n'appelle `context.recordSpeechSwitch()`** : la seule voie d'écriture est `recordSpeechSwitchForTest()`, réservée aux tests (`DetectionOrchestrator.kt:220`).

L'orchestrateur détecte bien des transitions (silence, pic d'énergie) dans `detectTransition()`, mais **ne les convertit jamais en événements de tour de parole** dans le `ConversationContext`. Aucun VAD (Voice Activity Detection) / détection de tour n'est câblé.

Conséquence : sur un dataset réel, R-01 est toujours exclue (confiance nulle), et le jalon PRD « premier switch de parole → verdict comportemental (confiance ≥ 90 %) » est **structurellement inatteignable**.

**Conséquence combinée C-1 + C-2 :** sur des fichiers réels, seule R-03 (NoiseLinearity) produit un signal exploitable. Pour de l'audio humain organique (non silencieux, non bouclé), R-03 → suspicion ≈ 0,05 et R-02 → 0,5 constant ⇒ `aiProbability` converge vers ≈ 0,3, **sous le seuil `aiThreshold` de 0,5**. Tout est donc classé HUMAN — y compris les fichiers AI. Le moteur **ne peut pas détecter une voix IA** aujourd'hui ; le harness rapportera surtout « la proportion de fichiers humains » comme précision apparente.

### 🔴 C-3 — Instances de règles partagées entre fichiers dans le harness (bug de correction)

Le contrat de `ChunkProcessor` promet : *« One instance is created per audio file so that each file benefits from a clean engine state »* (`ChunkProcessor.kt:11`). Or dans `ValidationRunner.main` :

```kotlin
// ValidationRunner.kt:182-189
val rules = buildProductionRules()          // ← construit UNE fois
val summary = runBlocking {
    ValidationRunner(
        datasetDir = datasetDir,
        processorFactory = {
            DetectionOrchestratorAdapter(DetectionOrchestrator(rules))  // ← réutilise les MÊMES instances
        }
    ).runValidation()
}
```

L'orchestrateur est bien recréé par fichier, mais les **règles sont des instances uniques partagées**. Or `NoiseLinearityRule` et `SpectralArtifactsRule` portent un état mutable inter-chunk :

```kotlin
// NoiseLinearityRule.kt:38-39
private var chunkCount = 0
private var previousAmplitudeProfile: FloatArray? = null
```

Effets sur le 2ᵉ fichier et suivants :
1. `chunkCount` n'est jamais remis à zéro ⇒ la confiance R-03 est déjà saturée dès le 1ᵉʳ chunk (rampe faussée).
2. `previousAmplitudeProfile` **fuit d'un fichier à l'autre** ⇒ le 1ᵉʳ chunk du fichier *N+1* est comparé au dernier chunk du fichier *N* ⇒ détection de boucle parasite (faux positif possible).

C'est une vraie régression : l'isolation par fichier annoncée est violée. Les tests ne l'attrapent pas car ils utilisent `FakeChunkProcessor` (sans état partagé) ou créent une nouvelle règle par test.

**Correctif recommandé :** instancier les règles *dans* la `processorFactory` (une fabrique par fichier), ou ajouter une réinitialisation explicite d'état de règle.

---

## 4. Constats moyens

### 🟠 M-1 — `computeRawConfidence` gonfle la confiance quand des règles sont exclues

```kotlin
// ScoreAggregator.kt:57-65
fun computeRawConfidence(contributions: List<RuleContribution>): Float {
    val totalWeight = contributions.sumOf { it.weight.toDouble() }   // ← somme des règles PRÉSENTES
    ...
    contributions.sumOf { (it.weight * it.confidence).toDouble() } / totalWeight
}
```

Le dénominateur n'utilise que les poids des règles **effectivement présentes** dans le chunk. Lorsqu'un early-exit ou l'échantillonnage intermittent retire les règles lourdes, il ne reste parfois que R-03 :

> contributions = { R-03 (poids 0,25, confiance 1,0) } ⇒ rawConfidence = (0,25 × 1,0) / 0,25 = **1,0**

Combiné au ratchet monotone (`peakConfidence = max(...)`), **un seul chunk en early-exit verrouille `globalConfidence` à 100 % de façon permanente**, alors même que R-01 et R-02 n'ont jamais voté. La jauge ne reflète donc plus « la maturité des preuves sur l'ensemble du jeu de règles ».

**Piste :** diviser par la somme des poids de **toutes les règles configurées** (les règles absentes comptant comme confiance 0), pour que l'exclusion d'une règle *abaisse* la confiance plutôt que de l'augmenter.

### 🟠 M-2 — `accuracy` par défaut à 1,0 : faux vert du harness

```kotlin
// ValidationRunner.kt:128
val accuracy = if (countable.isEmpty()) 1.0f else correctCount.toFloat() / countable.size
```

Un run qui ne décode aucun fichier, ou dont tous les verdicts sont sous le seuil de confiance, rapporte **« précision 100 % »**. Pour un banc de validation, c'est trompeur : il faudrait signaler `N/A` / indéfini (ou 0 explicitement avec un avertissement « aucun verdict exploitable »). Idem pour le FPR à 0,0.

### 🟠 M-3 — Aucun recall / matrice de confusion : le biais HUMAN reste invisible

`classifyState` traite toute confiance insuffisante comme HUMAN (`ValidationRunner.kt:116-120`), et le rapport n'expose que **précision + FPR**. Avec le stub C-1, les fichiers AI ne sont quasiment jamais signalés : le **FPR paraîtra excellent (≈ 0 %) précisément parce que rien n'est jamais classé AI**. Sans **recall / taux de détection IA / matrice de confusion**, un lecteur ne peut pas voir ce biais structurel.

**Recommandation :** ajouter au `ValidationSummary` le recall (rappel sur les fichiers AI), le nombre de vrais/faux positifs/négatifs, et idéalement une matrice de confusion 2×2.

---

## 5. Constats faibles / cosmétiques

- **F-1** — `"pcm"` figure dans `SUPPORTED_EXTENSIONS` (`ValidationRunner.kt:156`) mais `AudioSystem.getAudioInputStream` ne sait pas décoder du PCM brut sans en-tête : ces fichiers sont systématiquement « skipped ». Extension trompeuse → la retirer ou ajouter un vrai décodeur PCM brut.
- **F-2** — Logique `computeRms` dupliquée entre `DetectionOrchestrator.kt:146` et `NoiseLinearityRule.kt:54`. À factoriser (util DSP partagé).
- **F-3** — Incohérence ADR-03 ↔ code : l'ADR affirme « Rules Are Stateless Per-Chunk », mais R-02/R-03 conservent un état mutable inter-chunk (compteurs de rampe, profil précédent). C'est la racine de C-3 — à clarifier dans l'ADR *et* dans le code.
- **F-4** — Dérive documentaire dans `architecture.md` : (a) l'arborescence montre `domain/`, `rules/`… à la racine alors que le code est sous `src/main/kotlin/com/voiceguard/` ; (b) `SpectralClassifierPort` n'apparaît pas dans la liste des ports ; (c) les pourcentages de confiance (~20 % / ~70 % / ≥ 90 %) sont aspirationnels et ne correspondent pas aux valeurs calculées ; (d) R-02 est décrit comme tournant « sur le NPU Tensor G4 » alors que c'est un stub.
- **F-5** — Le banc `validateEngine` n'écrit que sur stdout ; **aucune sortie machine (JSON) ni code de sortie non nul si les KPI ne sont pas atteints**. La CI ne peut donc pas échouer automatiquement sur « précision < 85 % ». Envisager un `exitProcess(1)` conditionnel et/ou un rapport JSON.
- **F-6** — Avertissement de toolchain au build : *« Path for java installation '/usr/lib/jvm/openjdk-21' … does not contain a java executable »*. Auto-détection JDK bruyante ; documenter/fixer la toolchain (ex. `foojay-resolver`) éviterait ce bruit.
- **F-7** — `version = "1.0.0-SNAPSHOT"` (`voiceguard-engine/build.gradle.kts:6`) alors que le README se décrit comme « specification-first » et que seules les Phases 1 partielles sont implémentées. Versioning à aligner sur l'état réel.

---

## 6. Recommandations priorisées

1. **(C-3)** Déplacer la construction des règles dans la `processorFactory` pour garantir une instance neuve par fichier — *correctif rapide, fort impact sur la fiabilité des mesures*.
2. **(C-1)** Intégrer un vrai backend d'inférence pour R-02 (même un classifieur DSP JVM intermédiaire : FFT + features spectrales), afin que le harness mesure quelque chose de réel sans attendre la Phase 2 Android.
3. **(C-2)** Câbler une détection de tours de parole (VAD basé sur l'énergie/silence déjà calculée dans `detectTransition`) qui alimente `recordSpeechSwitch()`, pour activer R-01 sur dataset.
4. **(M-3 / M-2)** Enrichir `ValidationSummary` : recall, matrice de confusion, et remplacer le défaut « accuracy = 1.0 » par un état `N/A` explicite.
5. **(M-1)** Revoir la normalisation de `computeRawConfidence` sur l'ensemble des règles configurées.
6. **(F-4 / F-3)** Resynchroniser `architecture.md` et l'ADR-03 avec le code.

---

## 7. Conclusion

L'**ossature** de VoiceGuard-Engine est de très bonne facture : couches propres, tests sérieux, garde-fous corrects, discipline d'architecture vérifiée automatiquement. C'est une base saine.

Le **risque principal n'est pas la qualité du code, mais la maturité de la détection** : les deux signaux discriminants (spectral, comportemental) ne sont pas opérationnels dans le pipeline réel, ce qui rend le banc de validation Epic 3 incapable, en l'état, de prouver les KPI annoncés. Le bug d'état partagé (C-3) dégrade en plus la fiabilité des mesures multi-fichiers.

En traitant C-1/C-2/C-3 et en exposant un recall, le projet passerait d'« infrastructure de détection » à « détecteur réellement mesurable » — l'objectif implicite de l'Epic 3.