from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "src/main/java/org/enthusia/rep/rep/Commendation.java",
    '''    public void setPositive(boolean positive) { this.positive = positive; }
    public void setCategory(RepCategory category) {
        this.category = category == null ? (positive ? RepCategory.WAS_KIND : RepCategory.SCAMMED) : category.migratedCategory();
    }
    public void setReasonText(String reasonText) { this.reasonText = reasonText == null ? "" : reasonText; }
    public void setLastEditedAt(long lastEditedAt) { this.lastEditedAt = lastEditedAt; }
    public void setIpHash(String ipHash) { this.ipHash = ipHash; }
    public void setScoreValue(int scoreValue) { this.scoreValue = normalizeScoreValue(positive, scoreValue); }

    public Map<String, Object> serialize() {''',
    '''    public synchronized void setPositive(boolean positive) { this.positive = positive; }
    public synchronized void setCategory(RepCategory category) {
        this.category = category == null ? (positive ? RepCategory.WAS_KIND : RepCategory.SCAMMED) : category.migratedCategory();
    }
    public synchronized void setReasonText(String reasonText) { this.reasonText = reasonText == null ? "" : reasonText; }
    public synchronized void setLastEditedAt(long lastEditedAt) { this.lastEditedAt = lastEditedAt; }
    public synchronized void setIpHash(String ipHash) { this.ipHash = ipHash; }
    public synchronized void setScoreValue(int scoreValue) { this.scoreValue = normalizeScoreValue(positive, scoreValue); }

    public synchronized int applyUpdate(boolean newPositive, RepCategory newCategory, String newReasonText,
                                        long newLastEditedAt, String newIpHash) {
        int oldValue = scoreValue;
        boolean polarityChanged = positive != newPositive;
        int newValue = polarityChanged ? newCategory.defaultScoreValue() : oldValue;
        positive = newPositive;
        category = newCategory;
        reasonText = newReasonText == null ? "" : newReasonText;
        lastEditedAt = newLastEditedAt;
        ipHash = newIpHash;
        scoreValue = normalizeScoreValue(newPositive, newValue);
        return scoreValue - oldValue;
    }

    public synchronized Commendation snapshot() {
        return new Commendation(giver, target, positive, category, reasonText,
                createdAt, lastEditedAt, ipHash, scoreValue);
    }

    public synchronized Map<String, Object> serialize() {'''
)

replace_once(
    "src/main/java/org/enthusia/rep/rep/RepService.java",
    '''        int oldValue = existing.getScoreValue();
        boolean polarityChanged = existing.isPositive() != positive;
        int newValue = polarityChanged ? normalizedCategory.defaultScoreValue() : oldValue;
        int delta = newValue - oldValue;
        int oldScore = getScore(targetId);

        existing.setPositive(positive);
        existing.setCategory(normalizedCategory);
        existing.setReasonText(reasonText);
        existing.setLastEditedAt(now);
        existing.setIpHash(ipHash);
        existing.setScoreValue(newValue);''',
    '''        int delta = existing.applyUpdate(positive, normalizedCategory, reasonText, now, ipHash);
        int oldScore = getScore(targetId);'''
)

replace_once(
    "src/main/java/org/enthusia/rep/rep/RepService.java",
    '''    private Commendation cloneCommendation(Commendation commendation) {
        return new Commendation(
                commendation.getGiver(),
                commendation.getTarget(),
                commendation.isPositive(),
                commendation.getCategory(),
                commendation.getReasonText(),
                commendation.getCreatedAt(),
                commendation.getLastEditedAt(),
                commendation.getIpHash(),
                commendation.getScoreValue()
        );
    }''',
    '''    private Commendation cloneCommendation(Commendation commendation) {
        return commendation.snapshot();
    }'''
)

test_path = Path("src/test/java/org/enthusia/rep/rep/CommendationMigrationTest.java")
test = test_path.read_text(encoding="utf-8")
method = '''
    @Test
    void atomicUpdatePreservesWeightUntilPolarityChangesAndSnapshotIsDetached() {
        Commendation commendation = new Commendation(
                UUID.randomUUID(), UUID.randomUUID(), false, RepCategory.SCAMMED,
                "legacy", 1L, 1L, null, -1);

        assertEquals(0, commendation.applyUpdate(false, RepCategory.GRIEFED, "edited", 2L, "hash"));
        assertEquals(-1, commendation.getScoreValue());
        Commendation snapshot = commendation.snapshot();

        assertEquals(2, commendation.applyUpdate(true, RepCategory.WAS_KIND, "positive", 3L, null));
        assertEquals(1, commendation.getScoreValue());
        assertFalse(snapshot.isPositive());
        assertEquals(-1, snapshot.getScoreValue());
        assertEquals("edited", snapshot.getReasonText());
    }
'''
if "atomicUpdatePreservesWeightUntilPolarityChangesAndSnapshotIsDetached" not in test:
    test = test.rsplit("}", 1)[0] + method + "}\n"
    test_path.write_text(test, encoding="utf-8")
