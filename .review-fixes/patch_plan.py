from pathlib import Path

path = Path("src/main/java/org/enthusia/rep/integration/plan/PlanReputationDataExtension.java")
text = path.read_text(encoding="utf-8")
old = 'commendation.isPositive() ? "+1" : "-1"'
count = text.count(old)
if count != 2:
    raise SystemExit(f"expected 2 stale Plan score displays, found {count}")
text = text.replace(old, 'signedValue(commendation.getScoreValue())')
closing = "\n}"
position = text.rfind(closing)
if position < 0:
    raise SystemExit("Plan class closing brace not found")
helper = '''

    private String signedValue(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }'''
text = text[:position] + helper + text[position:]
path.write_text(text, encoding="utf-8")
