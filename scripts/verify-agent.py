"""Live routing acceptance: python3 scripts/verify-agent.py [base-url] [machine-name-or-UUID]."""

import json
import os
import sys
import urllib.request


def ask(base, question):
    request = urllib.request.Request(base.rstrip('/') + '/api/agent/chat',
        data=json.dumps({'question': question}).encode(),
        headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + os.environ['IIOT_ACCESS_TOKEN']}, method='POST')
    with urllib.request.urlopen(request, timeout=600) as response:
        return json.load(response)


def verify(base, machine):
    data_tools = {'getMachineStatus', 'getRecentAnomalies', 'queryTelemetryRange'}
    scenarios = [
        ('data', f'What is the latest vibration reading for {machine}?', True, False),
        ('knowledge', 'What does error E204 mean?', False, True),
        ('mixed', f"Is {machine}'s vibration reading normal, and what should I do if not?", True, True),
        ('neither', 'Hello! What can you help me with?', False, False),
    ]
    for name, question, needs_data, needs_knowledge in scenarios:
        result = ask(base, question)
        print(json.dumps({'scenario': name, 'question': question, 'response': result}, indent=2), flush=True)
        used = {e['tool'] for e in result['evidence'] if e['success']}
        assert bool(used & data_tools) == needs_data, result
        assert ('retrieveEquipmentKnowledge' in used) == needs_knowledge, result
        assert result['answer'].strip(), result
        assert result['insufficientEvidence'] is False, result
        cited = {e['tool'] for e in result['evidence'] if e['id'] in result['evidenceIds']}
        if needs_data:
            assert cited & data_tools, result
        if needs_knowledge:
            assert 'retrieveEquipmentKnowledge' in cited, result
        if name == 'knowledge':
            answer = result['answer'].lower()
            assert 'e204' in answer and 'sensor' in answer and 'temperature' in answer, result
            assert any(term in answer for term in ('unavailable', 'missing', 'invalid')), result
    print('All four routing checks passed. Review the printed answers against their evidence for semantic fidelity.')


if __name__ == '__main__':
    verify(sys.argv[1] if len(sys.argv) > 1 else 'http://localhost:8080',
           sys.argv[2] if len(sys.argv) > 2 else 'SIM-001')
