from pathlib import Path

path = Path("src/main/java/org/enthusia/rep/integration/plan/PlanReputationDataExtension.java")
text = path.read_text(encoding="utf-8")
old = 'commendation.isPositive() ? "+1" : "-1"'
count = text.count(old)
if count != 2:
    raise SystemExit(f"expected 2 stale Plan score displays, found {count}")
text = text.replace(old, 'signedValue(commendation.getScoreValue())')
anchor = '''        return table.build();
    }
}'''
replacement = '''        return table.build();
    }

    private String signedValue(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }
}'''
if anchor not in text:
    raise SystemExit("Plan helper anchor not found")
path.write_text(text.replace(anchor, replacement), encoding="utf-8")
