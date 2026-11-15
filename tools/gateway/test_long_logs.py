"""Real WebSocket -> fsync archive -> restart/replay, synthetic only."""
import asyncio, hashlib, json, os, tempfile, unittest, uuid
os.environ.setdefault('CARDECK_GATEWAY_TOKEN','synthetic-write-'+'w'*40)
os.environ.setdefault('CARDECK_GATEWAY_READ_TOKEN','synthetic-read-'+'r'*40)
import gateway
from log_archive import LogArchive, initialize_root
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

class LongLogs(unittest.IsolatedAsyncioTestCase):
 async def test_v2_lossless_boundary_replay_conflict_and_no_ack_on_failure(self):
  with tempfile.TemporaryDirectory() as root:
   initialize_root(root)
   await gateway.configure_archive({'root':root})
   try:
    async with gateway.serve(gateway.handler,'127.0.0.1',0,process_request=gateway.process_request,
                             max_size=16384,compression=None) as server:
     url='ws://127.0.0.1:'+str(server.sockets[0].getsockname()[1])+'/ws/device'
     device=str(uuid.uuid4());batch=str(uuid.uuid4());text='完整中文😀;'*700
     payload=dict(type='logs',schema_version=2,source='system',batch_id=batch,
                  user_confirmed=True,lines=[text])
     async def opened():
      ws=await connect(url,additional_headers={'Authorization':'Bearer '+gateway.TOKEN},proxy=None)
      assert 'logs_v2' in json.loads(await ws.recv())['capabilities']
      await ws.send(json.dumps(dict(type='register',device_id=device,app_version='synthetic',sdk=35)))
      assert json.loads(await ws.recv())['type']=='register_ack'
      return ws
     ws=await opened()
     await ws.send(json.dumps(payload,ensure_ascii=False))
     ack=json.loads(await ws.recv());self.assertEqual(ack['schema_version'],2)
     self.assertEqual(ack['accepted_lines'],1)
     await ws.close();await asyncio.sleep(.02)
     # Simulate a process restart of archive/index and volatile de-duplication state.
     await gateway.storage_call('close');gateway.archive=LogArchive(root)
     gateway.accepted_batches.clear()
     ws=await opened()
     await ws.send(json.dumps(payload,ensure_ascii=False))
     self.assertEqual(json.loads(await ws.recv())['batch_id'],batch)
     rows=await gateway.storage_call('tail',device,'system')
     self.assertEqual([r['text'] for r in rows],[text])
     await ws.send(json.dumps({**payload,'lines':['same id changed content']}))
     with self.assertRaises(ConnectionClosed):await ws.recv()
     self.assertEqual(len(await gateway.storage_call('tail',device,'system')),1)
     await asyncio.sleep(.02)
     ws=await opened()
     await ws.send(json.dumps({**payload,'batch_id':str(uuid.uuid4()),'lines':['字'*4097]},ensure_ascii=False))
     with self.assertRaises(ConnectionClosed) as closed:await ws.recv()
     self.assertEqual(closed.exception.code,1008)
     await asyncio.sleep(.02)
     ws=await opened()
     from unittest.mock import patch
     with patch.object(gateway.archive,'append_v2',side_effect=OSError('synthetic fsync failure')):
      await ws.send(json.dumps({**payload,'batch_id':str(uuid.uuid4())},ensure_ascii=False))
      with self.assertRaises(ConnectionClosed) as closed:await ws.recv()
      self.assertEqual(closed.exception.code,1011)
   finally:
    await gateway.storage_call('close')
    gateway.archive=None

class ArchiveCommitRecovery(unittest.TestCase):
 def test_directory_flush_failure_keeps_one_retryable_batch(self):
  import stat
  from pathlib import Path
  from unittest.mock import patch
  with tempfile.TemporaryDirectory() as root:
   initialize_root(root);device=str(uuid.uuid4());batch=str(uuid.uuid4())
   store=LogArchive(root);text='完整长日志;'*600;digest='a'*64
   real_fsync=os.fsync
   def fail_directory(fd):
    if stat.S_ISDIR(os.fstat(fd).st_mode):raise OSError('synthetic directory flush failure')
    return real_fsync(fd)
   try:
    with patch('os.fsync',side_effect=fail_directory):
     with self.assertRaises(OSError):store.append_v2(device,'app',[text],batch,None,digest)
    before=list(Path(root).rglob('*.jsonl'));self.assertEqual(len(before),1)
    rows=store.append_v2(device,'app',[text],batch,None,digest)
    self.assertEqual([r['text'] for r in rows],[text])
    self.assertEqual(list(Path(root).rglob('*.jsonl')),before)
    self.assertEqual(stat.S_IMODE(before[0].stat().st_mode),0o600)
   finally:store.close()

if __name__=='__main__':unittest.main()
