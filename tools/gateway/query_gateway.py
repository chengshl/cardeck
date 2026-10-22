"""Run inside the Gateway Pod; credentials stay in environment/memory."""
import json
import os
import urllib.request
import sys
import uuid

path = '/api/devices'
if len(sys.argv) == 3 and sys.argv[1] == '--logs':
    path = '/api/logs?device_id=' + str(uuid.UUID(sys.argv[2]))
elif len(sys.argv) != 1:
    raise SystemExit('Usage: query_gateway.py [--logs installation-uuid]')
request = urllib.request.Request('http://127.0.0.1:8080' + path,
    headers={'Authorization': 'Bearer ' + os.environ['CARDECK_GATEWAY_READ_TOKEN']})
with urllib.request.urlopen(request, timeout=5) as response:
    print(json.dumps(json.load(response), ensure_ascii=False, indent=2))
