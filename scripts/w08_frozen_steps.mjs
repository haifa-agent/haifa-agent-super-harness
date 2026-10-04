// Thin Node bridge: execute the unchanged TypeScript functions; assertions live in Python.
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const [source, input, output] = process.argv.slice(2);
if (!source || !input || !output) throw new Error('Expected steps.ts, fixture.json, output.json');
const fixture = JSON.parse(fs.readFileSync(input, 'utf8'));
const frozen = await import(pathToFileURL(path.resolve(source)).href);
fs.writeFileSync(output, JSON.stringify({
  live: fixture.live.map(event => frozen.messageToStep(event.message, event.message_index)),
  replay: frozen.eventsToSteps(fixture.replay, fixture.taskId),
}, null, 2));
