// Validates every example against its type in oversight.schema.json, and the fixtures against the
// pinned Verify schemas. An example is named <Type>.<case>.json; <Type> is a $defs entry.
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { parse } from "yaml";

const here = dirname(fileURLToPath(import.meta.url));
const read = (p) => readFileSync(join(here, p), "utf8");

const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
ajv.addSchema(JSON.parse(read("oversight.schema.json")), "oversight");
ajv.addSchema(parse(read("verify/overseer-registry.schema.yaml")), "overseers");
ajv.addSchema(JSON.parse(read("verify/human-config.schema.json")), "human-config");

let failures = 0;
const check = (label, ref, value) => {
  const validate = ajv.getSchema(ref);
  if (!validate) throw new Error(`no schema ${ref}`);
  if (validate(value)) return;
  failures++;
  console.error(`FAIL ${label}`);
  for (const e of validate.errors) console.error(`  ${e.instancePath || "/"} ${e.message}`);
};

const examples = readdirSync(join(here, "examples")).filter((f) => f.endsWith(".json"));
for (const file of examples) {
  const type = file.split(".")[0];
  check(`examples/${file}`, `oversight#/$defs/${type}`, JSON.parse(read(`examples/${file}`)));
}

const fixtures = readdirSync(join(here, "fixtures"));
for (const file of fixtures.filter((f) => f.startsWith("overseers"))) {
  check(`fixtures/${file}`, "overseers", parse(read(`fixtures/${file}`)));
}
for (const file of fixtures.filter((f) => f.startsWith("controls"))) {
  const doc = parse(read(`fixtures/${file}`));
  for (const control of doc.controls) {
    check(`fixtures/${file} ${control.id} human_config`, "human-config#/$defs/human_config", control.human_config);
    if (control.halt_config) {
      check(`fixtures/${file} ${control.id} halt_config`, "human-config#/$defs/halt_config", control.halt_config);
    }
  }
}

const patterns = parse(read("verify/human-patterns.yaml")).patterns;
for (const file of fixtures.filter((f) => f.startsWith("controls"))) {
  for (const control of parse(read(`fixtures/${file}`)).controls) {
    const pattern = patterns[control.human_config.pattern];
    if (!pattern.flavors.includes(control.flavor)) {
      failures++;
      console.error(`FAIL fixtures/${file} ${control.id}: ${control.flavor} is not a flavor of ${control.human_config.pattern}`);
    }
  }
}

console.log(`${examples.length} examples, ${fixtures.length} fixtures, ${failures} failures`);
process.exit(failures ? 1 : 0);
