---

# Product Requirement Document (PRD)

## Projet : VoiceGuard-Engine (Anti-Voice-Spam Core)

| Attribut | Détail |
| --- | --- |
| **Auteur** | Tom Walker |
| **Statut** | Spécifications Finalisées (Prêt pour R&D) |
| **Version** | 1.1.0 |
| **Date** | Mai 2026 |
| **Cible Technique** | Kotlin (JVM Pur ➔ Migration Android Local) |

---

## 1. Vision & Objectifs Métier

### 1.1 Contexte

Le paysage de la cybercriminalité téléphonique est bouleversé par les IA génératives grand public (Text-to-Speech et Voice Conversion). Les attaques par *vishing* (phishing vocal) sont automatisées, interactives et capables de simuler des voix humaines de manière presque indétectable pour l'oreille humaine.

### 1.2 Énoncé du Problème

Les solutions actuelles de blocage d'appels se basent uniquement sur la réputation des numéros de téléphone (listes noires). Elles sont impuissantes face aux numéros usurpés (*spoofing*) ou aux nouvelles lignes jetables. Il manque un système d'analyse comportementale et physique du signal audio en temps réel, capable de valider l'authenticité de l'interlocuteur directement sur le terminal.

### 1.3 Vision Produit

Développer un moteur de détection hybride, modulaire et ultra-léger (Core Engine) écrit en **Kotlin**, capable d'analyser un flux audio en continu pour calculer un score de suspicion et un indice de confiance, optimisé pour s'exécuter localement sur un appareil mobile (type Google Pixel 9a) sans impacter la batterie.

---

## 2. Portée du Projet (Scope)

### En Périmètre (Phase 1 : R&D et Core Engine)

* Architecture Hexagonale stricte en Kotlin (JVM / Gradle) isolant le domaine.
* Framework d'orchestration asynchrone basé sur les **Kotlin Coroutines** et les **StateFlows** pour le traitement réactif.
* Algorithme de pondération dynamique consolidant le score d'IA et l'indice de confiance globale.
* Modélisation de la courbe d'accumulation de la confiance dans le temps.
* Implémentation de 3 règles pilotes : Latence comportementale, Linéarité du bruit de fond, et Artefacts spectraux (via micro-modèle TFLite).
* Script d'intégration automatisé pour valider le moteur sur des datasets de recherche open-source.

### Hors Périmètre (Phase 2 : Intégration OS)

* Développement des composants UI graphiques de l'application Android (Jetpack Compose).
* Plomberie système pour l'interception des appels téléphoniques réels (`InCallService` ou contournements VoIP).

---

## 3. Spécifications Fonctionnelles & Expérience Utilisateur

### 3.1 Interface Réactive en Temps Réel (Double Jauge)

Pour éviter le syndrome de la "jauge folle" (qui sauterait de 0% à 100% au moindre grésillement), le moteur calcule et expose deux indicateurs distincts mis à jour deux fois par seconde (fréquence de traitement des *chunks* de 500ms) :

1. **Jauge 1 : Score de Confiance Globale (Maturité de l'analyse)**
* *Comportement :* Uniquement croissante ou stagnante. Elle commence à **0% au décroché** et s'incrémente au fur et à mesure que le temps passe et que les règles accumulent des échantillons valides.
* *Rôle :* Indiquer à l'utilisateur que le moteur est en train de récolter des preuves.


2. **Jauge 2 : Probabilité IA (Verdict)**
* *Comportement :* Dynamique et fluctuante. Sa valeur n'est jugée **crédible et exploitable graphiquement que si la jauge de confiance est haute**.



### 3.2 Modélisation de la Courbe d'Accumulation Temporelle

L'état de certitude du système évolue selon la chronologie suivante :

```
[0s : Décroché] ➔ [1-2s : Acoustique Initiale] ➔ [3-5s : Stabilisation] ➔ [Premier Switch : Verdict]
Confiance : 0%         Confiance : ~20%                Confiance : ~70%           Confiance : ≥ 90%
Probabilité IA : N/A   Bruit de fond linéaire          Analyses spectrales        Calcul de la latence

```

* **0 à 1s (Confiance = 0%) :** Phase de pré-chauffage. Les buffers se remplissent. L'affichage force la confiance à 0% pour éliminer les faux positifs liés aux bruits de commutation de ligne.
* **1 à 2s (Confiance $\approx$ 20%) :** Premiers indices physiques. La règle de bruit ambiant valide si la ligne présente un silence numérique parfait ou une boucle artificielle.
* **3 à 5s (Confiance $\approx$ 70%) :** Stabilisation acoustique. Le modèle spectral (TFLite) a analysé plusieurs phonèmes et traqué les répétitions d'artefacts des vocodeurs TTS. Une alerte modérée peut être émise si le score d'IA est élevé.
* **Au premier switch de parole (Confiance $\ge$ 90%) :** Phase comportementale. L'utilisateur parle, l'interlocuteur réagit. La règle de latence mesure le "ping" technique (STT + LLM + TTS). C'est le point de confirmation ou d'infirmation ultime du moteur.

### 3.3 Comportement face aux Faux Positifs

L'application a un rôle strictement **informatif**. Le moteur n'interrompt jamais un appel automatiquement. L'utilisateur garde le contrôle total de la communication.

---

## 4. Architecture Technique & Exigences Système

### 4.1 Modèle de Données & Ports du Domaine

Le moteur applique une isolation Clean Architecture rigoureuse via les structures suivantes :

```kotlin
data class AudioChunk(val pcmData: FloatArray, val sampleRate: Int = 16000)
data class RuleResult(val suspicionScore: Float, val confidence: Float)
class ConversationContext // Stockage de l'historique et des timestamps de parole

interface AudioDetectionRule {
    val name: String
    val weight: Float // Coefficient d'importance (0.0 à 1.0)
    suspend fun analyze(chunk: AudioChunk, context: ConversationContext): RuleResult
}

```

### 4.2 L'Orchestrateur Réactif et Formule du Score

L'orchestrateur distribue les chunks aux règles en parallèle via les Coroutines Kotlin. L'état consolidé est exposé à la UI via un `StateFlow<DetectionUiState>`.

Le score global d'IA est calculé par une moyenne pondérée dynamique. Une règle qui ne peut pas se prononcer (ex: la latence avant le premier échange) renvoie une `confidence = 0.0` et s'exclut d'elle-même du calcul :

$$\text{Score Global IA} = \frac{\sum (Score_i \times Poids_i \times Confiance_i)}{\sum (Poids_i \times Confiance_i)}$$

### 4.3 Optimisations Matérielles (Contraintes Mobiles)

Pour préserver l'autonomie du Google Pixel 9a (puce Tensor G4, batterie 5100 mAh), trois barrières de performance sont implémentées :

* **Early Exit (Circuit Court) :** Si des règles légères valident avec certitude la présence d'un humain (bruits ambiants organiques, respirations non linéaires), l'orchestrateur coupe l'exécution des règles lourdes.
* **Délégation TPU (TFLite) :** Les modèles de réseaux de neurones (inférence spectrale) sont compilés au format TensorFlow Lite pour s'exécuter exclusivement sur la TPU, maintenant l'usage CPU proche de zéro.
* **Échantillonnage intermittent :** Le moteur suspend l'analyse continue durant les monologues stables et se réactive lors des phases de transition (silences, switchs de parole).

---

## 5. Spécifications des Règles Pilotes (Phase 1)

| ID | Nom de la Règle | Type | Description | Poids |
| --- | --- | --- | --- | --- |
| **R-01** | `LatencyBehaviorRule` | Algorithmique / Contextuel | Calcule le temps de latence au switch de parole. Un délai de réaction fixe et répétitif (ex: constant entre 1.5s et 2.2s lié au traitement cloud de l'IA) déclenche le score de suspicion. Confiance à 0.0 tant qu'aucun switch n'a eu lieu. | **0.40** |
| **R-02** | `SpectralArtifactsRule` | IA Locale (TFLite) | Analyse le spectre fréquentiel à la recherche des anomalies géométriques de phase laissées par les vocodeurs de synthèse (signatures ElevenLabs, OpenAI). Confiance incrémentale à chaque chunk traité. | **0.35** |
| **R-03** | `NoiseLinearityRule` | Traitement du Signal | Analyse la texture du bruit de fond. Un silence numérique parfait (0 absolu) ou une boucle de bruit identique répétée trahit une génération artificielle. Confiance maximale dès les 2 premières secondes. | **0.25** |

---

## 6. Critères de Validation & Stratégie de Test

Le succès de la phase R&D sera validé par un script automatisé exécuté via Gradle sur le banc d'essai local.

### 6.1 Datasets cibles

* **Fake-or-Real (FoR) - version rerec :** Échantillons de voix d'IA réenregistrés à travers un canal acoustique pour émuler les dégradations d'une vraie ligne téléphonique.
* **Deepfake-Audio-Detection (Hugging Face) :** Échantillons récents intégrant les signatures d'ElevenLabs et de Kokoro TTS.

### 6.2 KPIs de Réussite Technique

* **Précision Globale (Accuracy) :** $\ge$ 85% de bonne classification (Humain vs IA) sur le dataset de test mixte.
* **Taux de Faux Positifs :** $\le$ 5% (Éviter les fausses alertes sur des humains avec un micro de mauvaise qualité).
* **Temps de calcul (Latency) :** L'orchestration et le rendu du score pour un chunk de 500ms d'audio ne doivent pas dépasser **50ms** en temps de calcul réel sur la JVM.

---