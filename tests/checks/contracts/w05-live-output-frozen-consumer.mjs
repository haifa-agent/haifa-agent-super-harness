import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import assert from 'node:assert/strict';
const wt=path.resolve(process.argv[3]);
const {MessageTupleManager}=await import(pathToFileURL(path.join(wt,'frontend/node_modules/@langchain/langgraph-sdk/dist/ui/messages.js')));
const {StreamManager}=await import(pathToFileURL(path.join(wt,'frontend/node_modules/@langchain/langgraph-sdk/dist/ui/manager.js')));
const files=JSON.parse(await readFile(process.argv[2],'utf8'));
const normalize=rows=>rows.map(r=>({id:r.id,type:r.type,content:r.content,tool_calls:r.tool_calls??[]}));
async function fold(frames){
  const tuples=new MessageTupleManager();
  const stream=new StreamManager(tuples,{throttle:false,filterSubagentMessages:true,subagentToolNames:['task']});
  await stream.enqueue(async()=> (async function*(){for(const frame of frames)yield frame;})(),{callbacks:{},initialValues:{messages:[]},getMessages:v=>v.messages??[],setMessages:(v,messages)=>({...v,messages})});
  return stream;
}
let streams=0,tasks=0,errors=0;
for(const file of files){
  const record=JSON.parse(await readFile(file,'utf8'));
  // @AfterEach raw recordings serialize the stream list directly; the named
  // per-case evidence object wraps the same list under `streams`.
  const recordedStreams=Array.isArray(record)?record:record.streams;
  assert.ok(Array.isArray(recordedStreams),`unrecognized actual recorder shape: ${file}`);
  for(const wire of recordedStreams){
    assert.equal(wire.terminal_end,true,`actual stream did not reach end: ${file}`);
    const frames=wire.frames.map(f=>({event:f.event,data:f.data?JSON.parse(f.data):null}));
    const durable=frames.filter(f=>f.event==='values').at(-1);
    assert.ok(durable&&Array.isArray(durable.data.messages),`actual terminal values missing: ${file}`);
    const stream=await fold(frames);
    const expectedError=frames.some(f=>f.event==='error');
    if(expectedError)errors++;else assert.equal(stream.state.error,undefined,`frozen consumer error: ${file}`);
    assert.deepEqual(normalize(stream.values.messages),normalize(durable.data.messages),`actual terminal tuples changed durable identities/content: ${file}`);
    assert.equal(new Set(stream.values.messages.map(r=>r.id)).size,stream.values.messages.length,`duplicate visible identities: ${file}`);
    const reloaded=await fold([durable]);
    reloaded.reconstructSubagents(reloaded.values.messages);
    assert.deepEqual(normalize(reloaded.values.messages),normalize(stream.values.messages),`reload folded a different conversation: ${file}`);
    for(const row of durable.data.messages){
      for(const call of row.tool_calls??[]){
        if(call.name!=='task')continue;
        const live=stream.getSubagent(call.id),reload=reloaded.getSubagent(call.id);
        assert.ok(live&&reload,`real task card missing in frozen consumer: ${file}`);
        assert.equal(live.status,reload.status,`task terminal state changed on reconstruct: ${file}`);
        assert.notEqual(live.status,'running',`terminal task stayed running: ${file}`);
        tasks++;
      }
    }
    streams++;
  }
}
assert.ok(streams>=7&&tasks>=1,'actual ordinary/retention/Child/Steer wire coverage missing');
console.log(JSON.stringify({result:'PASS',actualStreams:streams,actualTaskCards:tasks,expectedFailureStreams:errors,files:files.length,scope:'Source-bound actual HTTP/SSE producer recordings folded by installed frozen StreamManager; no browser UI or package GO'}));
