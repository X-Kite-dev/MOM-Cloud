#!/usr/bin/env python3
"""Emit a read-only PostgreSQL regression using the deployment mapper's actual regex literals.

Generate to a file, then run it with the MES service role through psql -X -v ON_ERROR_STOP=1.
This generator never connects to a database. The generated SQL always ends with ROLLBACK.
"""

import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def sql_text(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def generate(mapper: Path) -> str:
    root = ET.parse(mapper).getroot()
    select = root.find("./select[@id='missingControls']")
    if select is None:
        raise ValueError("missingControls query is absent")
    query = "".join(select.itertext())
    patterns = re.findall(r"\$node_literal\$(.*?)\$node_literal\$\s*,\s*'([^']*)'", query, re.DOTALL)
    if len(patterns) != 2:
        raise ValueError("expected the two actual index-predicate literal extractors")
    cases = [
        ("identical", "status='ACTIVE'", "status='ACTIVE'", True),
        ("cast outside literal", "status='ACTIVE'::text", "status='ACTIVE'", True),
        ("case must differ", "status='ACTIVE'", "status='active'", False),
        ("inner space must differ", "code='AB C'", "code='ABC'", False),
        ("edge spaces must differ", "code=' A '", "code='A'", False),
        ("escaped quote preserved", "code='O''Brien'", "code='O''Brien'", True),
        ("escaped quote must differ", "code='O''Brien'", "code='OBrien'", False),
        ("literal order preserved", "status IN ('ACTIVE','CLOSED')", "status IN ('CLOSED','ACTIVE')", False),
        ("no predicate", "", "", True),
    ]
    rows = ",\n        ".join(
        "(" + ", ".join([sql_text(name), sql_text(left), sql_text(right), str(expected).lower()]) + ")"
        for name, left, right, expected in cases
    )
    left_pattern, left_flags = patterns[0]
    right_pattern, right_flags = patterns[1]
    return f"""-- Generated from MesIdentifierNodeDeploymentMapper.xml; no catalog/business writes.
BEGIN READ ONLY;
DO $node_regex_test$
DECLARE
    left_pattern text := {sql_text(left_pattern)};
    left_flags text := {sql_text(left_flags)};
    right_pattern text := {sql_text(right_pattern)};
    right_flags text := {sql_text(right_flags)};
    fixture record;
    left_values text[];
    right_values text[];
BEGIN
    FOR fixture IN
        SELECT * FROM (VALUES
        {rows}
        ) cases(name, left_value, right_value, expected)
    LOOP
        SELECT ARRAY(SELECT literal.tokens[1]
            FROM regexp_matches(fixture.left_value, left_pattern, left_flags)
                WITH ORDINALITY literal(tokens, ord) ORDER BY literal.ord)
            INTO left_values;
        SELECT ARRAY(SELECT literal.tokens[1]
            FROM regexp_matches(fixture.right_value, right_pattern, right_flags)
                WITH ORDINALITY literal(tokens, ord) ORDER BY literal.ord)
            INTO right_values;
        IF (left_values = right_values) IS DISTINCT FROM fixture.expected THEN
            RAISE EXCEPTION 'literal comparison failed: %, left=%, right=%, expected=%',
                fixture.name, left_values, right_values, fixture.expected;
        END IF;
    END LOOP;
    RAISE NOTICE 'Deployment predicate literal regression: {len(cases)} cases passed';
END
$node_regex_test$;
ROLLBACK;
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mapper", type=Path, default=Path(__file__).resolve().parents[1]
                        / "main/resources/mapper/MesIdentifierNodeDeploymentMapper.xml")
    args = parser.parse_args()
    sys.stdout.write(generate(args.mapper))


if __name__ == "__main__":
    main()
