# Plan d'action — Correction des constats de relecture

**Date :** 2026-05-21
**Référence :** `RAPPORT-RELECTURE.md`
**Décision structurante :** abandon de toute inférence IA en Phase 1. La règle spectrale R-02 (TFLite) est **réécrite en traitement du signal pur (FFT/DSP)**, sans aucun outil d'IA. Les poids des règles restent inchangés (R-01 0,40 / R-02 0,35 / R-03 0,25) et le mécanisme d'early-exit conserve son utilité (le FFT reste l'opération la plus coûteuse).

---

## 0. Prérequis transverses

- **P-1 — Jeu de données réel labellisé.** Aujourd'hui la validation n'a tourné que sur des WAV synthétiques (tests). Pour mesurer réellement les KPI, il faut un dataset étiqueté `real/` + `fake/` (ex. FoR-rerec, HuggingFace Deepfake) posé hors dépôt. *Bloquant pour toute mesure de précision/recall réelle.*
- **P-2 — Re-baseliner les KPI.** Sans analyse IA, la détection repose sur des heuristiques DSP + comportementales. L'objectif « précision ≥ 85 % » devra être confirmé ou réajusté une fois la nouvelle chaîne mesurée (voir Phase 1/2). À acter explicitement dans le PRD.

---

## Phase 1 — Fiabiliser le banc de mesure (avant de toucher la détection)

> Objectif : pouvoir *voir* l'effet des changements de détection. Aucun risque sur le domaine.

### A1 — (C-3) Instancier les règles par fichier
- **Quoi :** déplacer la construction des règles dans la `processorFactory` pour qu'AUCUN état ne fuie entre fichiers.
- **Où :** `ValidationRunner.kt:182-211` (`main` / `buildProductionRules`).
- **Comment :** transformer `buildProductionRules()` en lambda appelée *dans* la factory : `processorFactory = { DetectionOrchestratorAdapter(DetectionOrchestrator(buildProductionRules())) }`. Chaque fichier obtient des règles neuves (`chunkCount`, profils, état VAD remis à zéro).
- **Acceptation :** un test multi-fichiers où le fichier 2 démarre avec une confiance R-03 non saturée et un `previousAmplitudeProfile` nul (pas de détection de boucle inter-fichiers).
- **Effort :** S.

### A2 — (M-3) Recall + matrice de confusion dans le rapport
- **Quoi :** exposer recall (rappel sur fichiers AI), vrais/faux positifs/négatifs, matrice 2×2.
- **Où :** `ValidationSummary.kt`, `ValidationRunner.buildSummary` (`ValidationRunner.kt:122-153`).
- **Comment :** ajouter `truePositives`, `falseNegatives`, `recall`, et imprimer la matrice dans `printReport()`. Sans ça, le biais « tout classé HUMAN » reste invisible.
- **Acceptation :** sur un dataset mixte, le rapport affiche recall et matrice ; un run « tout HUMAN » montre recall = 0 % malgré une précision élevée.
- **Effort :** S.

### A3 — (M-2) Supprimer le faux-vert « accuracy = 1.0 »
- **Quoi :** quand `countableVerdicts == 0`, renvoyer un état explicite *indéfini* (ex. `Float.NaN` typé + libellé `N/A`, ou un champ `metricsAvailable: Boolean`) plutôt que 1,0 / 0,0.
- **Où :** `ValidationRunner.kt:128,132`, `ValidationSummary.printReport`.
- **Acceptation :** un run sans verdict exploitable imprime « Précision : N/A (aucun verdict exploitable) ».
- **Effort :** S.

### A4 — (F-5) Sortie exploitable par la CI
- **Quoi :** code de sortie ≠ 0 si KPI non atteints + rapport machine (JSON optionnel).
- **Où :** `ValidationRunner.main` (`ValidationRunner.kt:170-193`).
- **Comment :** comparer accuracy/FPR/recall aux seuils ; `exitProcess(1)` si échec ; option `-PreportJson=path`.
- **Acceptation :** `validateEngine` échoue le build quand la précision passe sous le seuil.
- **Effort :** S/M.

---

## Phase 2 — Réécriture de l'axe spectral (R-02) sans IA

> Supprime C-1. Conserve la frontière hexagonale via `SpectralClassifierPort`.

### B1 — Retirer toute la mécanique TFLite
- **Supprimer :**
  - `adapters/TFLiteSpectralAdapter.kt`
  - `test/.../adapters/TFLiteSpectralAdapterTest.kt`
- **Conserver :** `SpectralClassifierPort` (le *seam* reste utile pour injecter un faux en test) et `FakeSpectralClassifier` (test).
- **Note dépendances :** rien à retirer dans Gradle — aucune dépendance TensorFlow n'a jamais été déclarée (le TFLite n'existait qu'en commentaire/placeholder). `mp3spi` reste (décodage audio, pas de l'IA).

### B2 — Nouvelle implémentation DSP du port
- **Quoi :** créer `adapters/FftSpectralClassifier.kt` (ou `dsp/`) implémentant `SpectralClassifierPort` en FFT pur JVM.
- **Heuristiques candidates (sans IA) :** énergie hors bande / coupure passe-bas nette (signature de vocodeur band-limité), platitude spectrale, périodicité/tuilage harmonique, ratio harmoniques/bruit. Score ∈ [0,1].
- **Implémentation FFT :** FFT maison (Cooley-Tukey) en pur Kotlin, ou une lib DSP non-IA. Fenêtrage (Hann) sur le chunk 500 ms.
- **Où branché :** `ValidationRunner.buildProductionRules` injecte `FftSpectralClassifier()` dans `SpectralArtifactsRule`.
- **Acceptation :** tests unitaires DSP — un signal band-limité/synthétique scoré haut, un signal large bande « organique » scoré bas ; déterministe.
- **Effort :** L (cœur du travail).

### B3 — Adapter `SpectralArtifactsRule`
- **Quoi :** garder la rampe de confiance, mais vérifier que la règle reste correcte avec un classifieur DSP (et que son `chunkCount` est bien réinitialisé par fichier — cf. A1).
- **Où :** `rules/SpectralArtifactsRule.kt`.
- **Effort :** S.

### B4 — (F-4) Mettre à jour `architecture.md`
- Remplacer la description « TFLite / NPU Tensor G4 » de R-02 par l'approche DSP/FFT.
- Corriger : arborescence des packages (réel : `src/main/kotlin/com/voiceguard/…`), ajouter `SpectralClassifierPort` à la liste des ports, marquer les pourcentages de confiance (~20/70/90 %) comme indicatifs.
- **Effort :** S.

---

## Phase 3 — Activer la règle comportementale R-01 (VAD)

> Supprime C-2. Sans IA : VAD basé sur l'énergie/silence déjà calculée.

### C1 — Détection de tours de parole dans l'orchestrateur
- **Quoi :** convertir les franchissements silence→parole en `recordSpeechSwitch()`.
- **Où :** `DetectionOrchestrator.detectTransition` / `processChunk` (`DetectionOrchestrator.kt:87-194`).
- **Comment :** suivre l'état parlant/silencieux ; après un silence ≥ N ms suivi d'un retour de parole, enregistrer un switch. La détection de silence existe déjà (`SILENCE_RMS_THRESHOLD`).
- **Effort :** M.

### C2 — Horodatage sur la timeline AUDIO (point critique)
- **Quoi :** `recordSpeechSwitch()` doit recevoir un timestamp dérivé de `totalElapsedMicros` (temps audio écoulé), **pas** `System.currentTimeMillis()`.
- **Pourquoi :** `LatencyBehaviorRule` compare des *intervalles en ms* aux fenêtres IA (1500–2200 ms) vs humaine (180–350 ms). Sur replay de dataset, seuls les timestamps audio rendent la mesure déterministe et représentent les vrais silences conversationnels.
- **Où :** signature de `ConversationContext.recordSpeechSwitch` + appel orchestrateur ; aligner `recordSpeechSwitchForTest` sur la même base.
- **Acceptation :** un WAV contenant des silences espacés de ~1,8 s produit des intervalles → R-01 confiance > 0 et suspicion élevée, de façon reproductible.
- **Effort :** M.

### C3 — Vérifier l'interaction avec l'échantillonnage intermittent
- R-01 est `isHeavyAnalysis = true` : pendant un monologue stable elle est suspendue (légitime, pas de tour de parole). Vérifier que la reprise sur transition réactive bien R-01. Tests `DetectionOrchestratorEarlyExitTest` à étendre.
- **Effort :** S.

---

## Phase 4 — Cohérence du scoring & nettoyage

### D1 — (M-1) Normaliser `computeRawConfidence` sur le jeu de règles complet
- **Quoi :** diviser par la somme des poids de **toutes les règles configurées**, pas seulement des présentes, pour qu'une règle exclue *abaisse* la confiance au lieu de la gonfler à 100 %.
- **Où :** `ScoreAggregator.computeRawConfidence` (`ScoreAggregator.kt:57-65`) — passer le poids total de référence en paramètre depuis l'orchestrateur.
- **Acceptation :** un chunk en early-exit (seule R-03 présente) ne fait plus bondir `globalConfidence` à 1,0 ; le test `ScoreAggregatorTest` est mis à jour en conséquence.
- **Effort :** S/M.

### D2 — (F-3) Aligner ADR-03 et réalité
- **Quoi :** l'ADR-03 affirme « rules stateless per-chunk », faux pour R-02/R-03 (compteurs de rampe, profil précédent). Documenter que les règles portent un état *par flux/fichier* et exigent une instance neuve par fichier (lien avec A1).
- **Où :** `architecture.md` ADR-03 + KDoc des règles.
- **Effort :** S.

### D3 — (F-1) Extension `pcm` trompeuse
- Retirer `"pcm"` de `SUPPORTED_EXTENSIONS` (`ValidationRunner.kt:156`) — `AudioSystem` ne décode pas le PCM brut sans en-tête — ou implémenter un vrai décodeur PCM brut. Recommandé : retirer.
- **Effort :** S.

### D4 — (F-2) Factoriser `computeRms`
- Extraire la logique RMS dupliquée (`DetectionOrchestrator.kt:146`, `NoiseLinearityRule.kt:54`) dans un util DSP partagé.
- **Effort :** S.

### D5 — (F-6 / F-7) Hygiène build
- F-6 : configurer la toolchain JDK (ex. plugin `foojay-resolver`) pour supprimer l'avertissement « does not contain a java executable ».
- F-7 : aligner `version` (`voiceguard-engine/build.gradle.kts:6`) sur l'état réel du projet.
- **Effort :** S.

---

## Séquencement recommandé & dépendances

```
Phase 1 (banc fiable)  ──►  Phase 2 (spectral DSP)  ──►  Phase 3 (VAD/R-01)  ──►  Phase 4 (scoring & doc)
   A1 (bloquant multi-fichiers)          B1→B2→B3            C1→C2→C3              D1 dépend de Phase 2/3
   A2/A3 (visibilité)                     B4 (doc)            (réutilise RMS/silence)   D2..D5 (nettoyage)
   A4 (CI)
P-1 (dataset réel) requis avant toute mesure de KPI en fin de Phase 2 et Phase 3.
```

**Ordre logique :** d'abord rendre le banc honnête (Phase 1, surtout A1+A2), car sans recall ni isolation par fichier on ne peut pas juger l'effet des phases suivantes. Ensuite restaurer un signal réel (Phase 2 puis 3). Enfin corriger la sémantique de confiance et nettoyer (Phase 4).

## Impacts & risques

- **Capacité de détection :** R-03 (DSP linéarité) + nouvelle R-02 (DSP spectral) + R-01 (latence comportementale) sont des heuristiques. Elles attrapent les artefacts grossiers (band-limiting, silence parfait, boucles, latence de bot fixe) mais resteront probablement en retrait face à des deepfakes de pointe. **D'où P-2 : re-baseliner le KPI 85 %.**
- **Effort dominant :** B2 (FFT + heuristiques spectrales) et C1/C2 (VAD + horodatage audio). Le reste est essentiellement du nettoyage à faible risque.
- **Non-régression :** les 104 tests actuels servent de filet ; chaque phase ajoute ses tests. Lancer `./gradlew test` après chaque item.

## Récapitulatif par constat du rapport

| Constat | Traité par | Phase |
|---------|-----------|-------|
| C-1 (R-02 stub) | B1+B2+B3 (réécriture DSP) | 2 |
| C-2 (R-01 inactive) | C1+C2+C3 (VAD) | 3 |
| C-3 (instances partagées) | A1 | 1 |
| M-1 (confiance gonflée) | D1 | 4 |
| M-2 (accuracy 1.0) | A3 | 1 |
| M-3 (pas de recall) | A2 | 1 |
| F-1 (`pcm`) | D3 | 4 |
| F-2 (RMS dupliqué) | D4 | 4 |
| F-3 (ADR-03) | D2 | 4 |
| F-4 (doc archi) | B4 | 2 |
| F-5 (sortie CI) | A4 | 1 |
| F-6 (toolchain) | D5 | 4 |
| F-7 (version) | D5 | 4 |
