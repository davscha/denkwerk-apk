# Denkwerk – To-do

## Version 0.1 (Grundgerüst) ✅
- [x] Projekt anlegen (Kotlin, Compose) + GitHub Actions → Release „latest“
- [x] KI-Schicht aus Character Fun: `ChatEngine` mit LiteRT-LM (Gemma 4 E2B/E4B) und Ollama
- [x] Einstellungen: Modelle laden/importieren, Ollama-Verbindung, Modell-Liste, Vision-Check
- [x] Chat mit Mitschreiben (Streaming), Stopp, Markdown, Code-Blöcke mit Kopieren und Farben
- [x] Verlauf als JSON-Dateien, Seitenleiste mit Suche, Gruppen (Heute/Gestern/…), Anheften, Umbenennen, Löschen
- [x] Bilder anhängen (Vision) – Knopf grau ohne Bild-Fähigkeit
- [x] Neu erzeugen, eigene Nachricht bearbeiten
- [x] Modellwechsel oben im Chat (Gerät + Ollama)
- [x] Spracheingabe (Mikrofon) und Vorlesen (Android-Stimme)
- [x] Tablet-Layout (Seitenleiste fest) / Handy (Schublade)
- [x] Automatischer Chat-Titel (Ollama), sonst erste Wörter
- [x] Gemma-Modelle aus CharacterFun/Modelle mitbenutzen

## Werkzeuge (Tools) ✅
- [x] Tool-Schleife für Ollama-Modelle mit „tools“-Fähigkeit
- [x] uhrzeit, rechnen, web_suche (DuckDuckGo / SearXNG), seite_lesen, bilder_auf_seite, herunterladen (mit Rückfrage)
- [x] Gedächtnis: Fakten über dich in jedem Chat, Werkzeuge merken / vergessen, „Merk dir …“ ohne Tools
- [ ] ComfyUI-Bild malen als Werkzeug
- [ ] Timer, Wecker, Kalender
- [x] Videosuche mit Kärtchen + YouTube-Videos über Untertitel verstehen
- [x] Bild-Rückwärtssuche: Google Lens, Yandex-Werkzeug, SauceNAO (optional)
- [x] Danbooru-Tag-Liste: Prompt-Schmied, tags_pruefen/tags_suchen, Vorschläge beim Tippen
- [ ] Python auf dem PC ausführen

## Als Nächstes
- [x] Verzweigungen bei „Neu erzeugen“ (‹ 1/3 ›)
- [x] Eigene Assistenten / GPTs (Symbol, Anweisung, Temperatur, Lieblingsmodell, Gesprächs-Starter)
- [x] Assistenten mit KI-Hilfe erstellen (Stichpunkte → fertige Anweisung)
- [x] Schleifen-Schutz: Wiederholungen erkennen und stoppen, Ollama-Kontext wählbar (4k–32k)
- [ ] Assistenten teilen (Export/Import als Datei)
- [x] Prompt-Bibliothek mit `/`-Befehl
- [x] Mehrere Bilder pro Nachricht (Ollama bis 8, Gerät 1)
- [x] Dokumente anhängen (PDF, Word, Text, Code, CSV) – „Frag dein Dokument“
- [x] Memory: Fakten über dich, die in jeden Chat mitgehen
- [ ] Kontextanzeige (z. B. 3.2k/4k Tokens)
- [ ] Export als PDF, Backup/Wiederherstellen als JSON
- [ ] Oberfläche in Deutsch, Englisch, Spanisch, Französisch
- [ ] OpenAI-kompatible Server (LM Studio, llama.cpp)
- [x] Bilder malen über ComfyUI (NoobAI, Ordner denkwerk/noobai, Char- und Style-LoRAs)
- [x] Testphase: Anima-Workflow (AnimaPhotoTurbo, Qwen-Encoder/VAE), Breite × Höhe statt Format (Standard 960 × 1280); NoobAI-Checkpoints/LoRAs vorerst entfernt
- [x] Seed: „das gleiche Bild, aber …“ behält den Seed, 🔒 am Bild hält ihn fest
- [x] Malen auch mit dem internen Modell (App erkennt Malwünsche, Modell schreibt nur die Tags)
