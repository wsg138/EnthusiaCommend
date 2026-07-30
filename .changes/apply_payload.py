from __future__ import annotations

import base64
import gzip
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

RAW_FILES = {
    ".changes/raw/build.yml": ".github/workflows/build.yml",
    ".changes/raw/pom.xml": "pom.xml",
    ".changes/raw/RepScoreChangedEvent.java": "src/main/java/org/enthusia/rep/events/RepScoreChangedEvent.java",
    ".changes/raw/RepCategory.java": "src/main/java/org/enthusia/rep/rep/RepCategory.java",
    ".changes/raw/RepRules.java": "src/main/java/org/enthusia/rep/rep/RepRules.java",
    ".changes/raw/PluginDataSnapshot.java": "src/main/java/org/enthusia/rep/storage/PluginDataSnapshot.java",
    ".changes/raw/config.yml": "src/main/resources/config.yml",
    ".changes/raw/messages.yml": "src/main/resources/messages.yml",
    ".changes/raw/plugin.yml": "src/main/resources/plugin.yml",
    ".changes/raw/Commendation.java": "src/main/java/org/enthusia/rep/rep/Commendation.java",
    ".changes/raw/DiscordWebhookService.java": "src/main/java/org/enthusia/rep/discord/DiscordWebhookService.java",
    ".changes/raw/YamlPluginDataStore.java": "src/main/java/org/enthusia/rep/storage/YamlPluginDataStore.java",
    ".changes/raw/RepConfig.java": "src/main/java/org/enthusia/rep/config/RepConfig.java",
    ".changes/raw/CommendationMigrationTest.java": "src/test/java/org/enthusia/rep/rep/CommendationMigrationTest.java",
    ".changes/raw/RemovedRepMigrationTest.java": "src/test/java/org/enthusia/rep/rep/RemovedRepMigrationTest.java",
    ".changes/raw/RepRulesTest.java": "src/test/java/org/enthusia/rep/rep/RepRulesTest.java",
}

GZIP_B64_FILES = {
    ".changes/gz/CommendPlugin.java.gz.b64": "src/main/java/org/enthusia/rep/CommendPlugin.java",
    ".changes/gz/CommendCommand.java.gz.b64": "src/main/java/org/enthusia/rep/command/CommendCommand.java",
    ".changes/gz/RepService.java.gz.b64": "src/main/java/org/enthusia/rep/rep/RepService.java",
    ".changes/gz/RepEffectManager.java.gz.b64": "src/main/java/org/enthusia/rep/effects/RepEffectManager.java",
}


def write_file(source: Path, destination: Path, content: bytes) -> None:
    if not source.is_file():
        raise FileNotFoundError(f"Missing payload: {source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(content)


for source_name, destination_name in RAW_FILES.items():
    source = ROOT / source_name
    write_file(source, ROOT / destination_name, source.read_bytes())

for source_name, destination_name in GZIP_B64_FILES.items():
    source = ROOT / source_name
    encoded = source.read_text(encoding="utf-8").strip()
    decoded = gzip.decompress(base64.b64decode(encoded))
    write_file(source, ROOT / destination_name, decoded)

shutil.rmtree(ROOT / ".changes")
(ROOT / ".github/workflows/apply-payload.yml").unlink(missing_ok=True)
