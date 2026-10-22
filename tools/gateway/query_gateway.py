"""Run inside the Gateway Pod; credentials stay in environment/memory."""
import json
import os
import urllib.request

request = urllib.request.Request('http://127.0.0.1:8080/api/devices',
    headers={'Authorization': 'Bearer ' + os.environ['CARDECK_GATEWAY_READ_TOKEN']})
with urllib.request.urlopen(request, timeout=5) as response:
    print(json.dumps(json.load(response), ensure_ascii=False, indent=2))
