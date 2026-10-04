// Fails if src/api/schema.d.ts is out of date with ../docs/openapi.json (used in CI).
// The backend's ApiContractIT keeps docs/openapi.json in sync with the code; this keeps the
// frontend's generated types in sync with docs/openapi.json. Together: no silent drift.
import { execFileSync } from 'node:child_process'
import { mkdirSync, readFileSync } from 'node:fs'

const generated = 'node_modules/.tmp/schema.check.d.ts'
mkdirSync('node_modules/.tmp', { recursive: true })
execFileSync('npx', ['openapi-typescript', '../docs/openapi.json', '-o', generated], { stdio: 'ignore' })

if (readFileSync(generated, 'utf8') !== readFileSync('src/api/schema.d.ts', 'utf8')) {
  console.error('src/api/schema.d.ts is stale. Run: npm run gen:api')
  process.exit(1)
}
console.log('API types are up to date with docs/openapi.json')
