import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const scenario = __ENV.SCENARIO || 'smoke';
const baseUrl = __ENV.BASE_URL;
const measuredRequests = new Counter('workload_requests');
const unexpectedErrors = new Rate('unexpected_errors');
const expectedSettlementConflicts = new Counter('expected_settlement_conflicts');
const createdSettlements = new Counter('created_settlements');

const endpointMetrics = {
	groups_list: 'read',
	expenses_list: 'read',
	balances: 'read',
	settlements_list: 'read',
	settlement_suggestions: 'read',
	expense_create: 'write',
	settlement_create: 'write',
};
const endpointTrends = {};
const endpointCounters = {};
Object.entries(endpointMetrics).forEach(([endpoint, operation]) => {
	endpointTrends[endpoint] = new Trend(`latency_${endpoint}_ms`, true);
	endpointCounters[endpoint] = new Counter(`requests_${endpoint}`);
});

export const options = {
	thresholds: {
		checks: ['rate==1'],
		unexpected_errors: ['rate<0.01'],
		'http_req_duration{operation:read}': ['p(95)<750'],
		'http_req_duration{operation:write}': ['p(95)<1500'],
	},
};

if (scenario === 'smoke') {
	options.vus = 1;
	options.iterations = 1;
} else if (scenario === 'read') {
	options.scenarios = {
		staged_read: {
			executor: 'ramping-vus',
			startVUs: 1,
			stages: [
				{ duration: '20s', target: 1 },
				{ duration: '20s', target: 3 },
				{ duration: '20s', target: 5 },
				{ duration: '30s', target: 10 },
				{ duration: '5s', target: 0 },
			],
			gracefulRampDown: '5s',
		},
	};
} else if (scenario === 'mixed') {
	options.scenarios = {
		mixed_workload: {
			executor: 'ramping-vus',
			startVUs: 1,
			stages: [
				{ duration: '10s', target: 1 },
				{ duration: '10s', target: 3 },
				{ duration: '40s', target: 5 },
				{ duration: '5s', target: 0 },
			],
			gracefulRampDown: '5s',
		},
	};
} else if (scenario === 'contention') {
	options.scenarios = {
		settlement_contention: {
			executor: 'per-vu-iterations',
			vus: 8,
			iterations: 1,
			maxDuration: '30s',
		},
	};
	options.thresholds.expected_settlement_conflicts = ['count==7'];
	options.thresholds.created_settlements = ['count==1'];
} else {
	throw new Error(`Unsupported audit scenario: ${scenario}`);
}

http.setResponseCallback(http.expectedStatuses(200, 201, 204, 409));

function expectStatus(response, expected, label) {
	const passed = response !== null && response.status === expected;
	check(response, { [label]: () => passed });
	if (!passed) {
		throw new Error(`${label}: expected HTTP ${expected}, received ${response ? response.status : 'no response'}.`);
	}
	return response.json();
}

function request(method, path, token, body, endpoint, operation, acceptedStatuses, measured = true) {
	const params = {
		headers: { Accept: 'application/json' },
		tags: { endpoint, operation: measured ? operation : 'setup' },
		timeout: '15s',
	};
	if (token) {
		params.headers.Authorization = `Bearer ${token}`;
	}
	if (body !== null) {
		params.headers['Content-Type'] = 'application/json';
	}

	const response = method === 'GET'
		? http.get(`${baseUrl}${path}`, params)
		: http.request(method, `${baseUrl}${path}`, body === null ? null : JSON.stringify(body), params);

	const accepted = acceptedStatuses || [200, 201, 204];
	const passed = response !== null && accepted.includes(response.status);
	if (measured) {
		measuredRequests.add(1, { endpoint, operation });
		unexpectedErrors.add(!passed, { endpoint, operation });
		if (endpointTrends[endpoint]) {
			endpointTrends[endpoint].add(response ? response.timings.duration : 0, { operation });
			endpointCounters[endpoint].add(1, { operation });
		}
	}
	check(response, { [`${endpoint} returned an expected status`]: () => passed });
	return response;
}

function uuid() {
	return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
		const random = Math.floor(Math.random() * 16);
		const value = character === 'x' ? random : (random & 0x3) | 0x8;
		return value.toString(16);
	});
}

function minorUnits(amount) {
	const numericAmount = Number(amount);
	return Number.isFinite(numericAmount) ? Math.round(numericAmount * 100) : Number.NaN;
}

function setupRequest(method, path, token, body, endpoint, expectedStatus) {
	const response = request(method, path, token, body, endpoint, 'setup', [expectedStatus], false);
	return expectStatus(response, expectedStatus, `Fixture setup ${endpoint}`);
}

export function setup() {
	if (!baseUrl || !baseUrl.startsWith('http://127.0.0.1:')) {
		throw new Error('The performance audit only permits an HTTP loopback API URL.');
	}

	const suffix = `${__ENV.RUN_ID}-${scenario}-${Date.now()}`;
	const password = 'AuditOnly-Phase5-29!';
	const register = (role) => setupRequest(
		'POST',
		'/api/auth/register',
		'',
		{
			name: `Phase 5 ${role}`,
			email: `phase5-${suffix}-${role}@example.invalid`,
			password,
		},
		'fixture_register',
		201,
	);
	const owner = register('owner');
	const member = register('member');
	const login = (email) => setupRequest(
		'POST',
		'/api/auth/login',
		'',
		{ email, password },
		'fixture_login',
		200,
	).accessToken;
	const ownerEmail = `phase5-${suffix}-owner@example.invalid`;
	const memberEmail = `phase5-${suffix}-member@example.invalid`;
	const ownerToken = login(ownerEmail);
	const memberToken = login(memberEmail);
	const group = setupRequest(
		'POST',
		'/api/groups',
		ownerToken,
		{ name: `Phase 5 ${suffix}` },
		'fixture_group',
		201,
	);
	const groupId = group.id;

	setupRequest(
		'POST',
		`/api/groups/${groupId}/members`,
		ownerToken,
		{ email: memberEmail },
		'fixture_member',
		201,
	);
	setupRequest(
		'POST',
		`/api/groups/${groupId}/expenses`,
		ownerToken,
		{
			description: 'Phase 5 baseline expense',
			amount: '100.00',
			paidByUserId: owner.id,
			currencyCode: 'INR',
			splitType: 'EXACT',
			splits: [
				{ userId: owner.id, amount: '50.00' },
				{ userId: member.id, amount: '50.00' },
			],
		},
		'fixture_expense',
		201,
	);

	return {
		groupId,
		ownerId: owner.id,
		memberId: member.id,
		ownerToken,
		memberToken,
		contentionRequestId: uuid(),
	};
}

function groupPath(data, resource) {
	return `/api/groups/${data.groupId}/${resource}`;
}

function get(path, token, endpoint) {
	const response = request('GET', path, token, null, endpoint, 'read', [200]);
	return response.status === 200 ? response.json() : null;
}

function createExpense(data, token, payerId, description, amount) {
	const half = (Number(amount) / 2).toFixed(2);
	return request(
		'POST',
		groupPath(data, 'expenses'),
		token,
		{
			description,
			amount,
			paidByUserId: payerId,
			currencyCode: 'INR',
			splitType: 'EXACT',
			splits: [
				{ userId: data.ownerId, amount: half },
				{ userId: data.memberId, amount: half },
			],
		},
		'expense_create',
		'write',
		[201],
	);
}

export default function (data) {
	if (scenario === 'smoke') {
		const expenses = get(groupPath(data, 'expenses'), data.ownerToken, 'expenses_list');
		const baseline = check(expenses, {
			'baseline expense persisted with exact split total': (items) => Array.isArray(items)
				&& items.length === 1
				&& minorUnits(items[0].amount) === 10000
				&& items[0].splits.reduce((total, split) => total + minorUnits(split.amount), 0) === 10000,
		});
		const balances = get(groupPath(data, 'balances'), data.ownerToken, 'balances');
		const initialBalances = check(balances, {
			'initial group balances reconcile': (value) => value.groupId === data.groupId
				&& value.balances.length === 2
				&& value.balances.reduce((total, member) => total + minorUnits(member.netBalance), 0) === 0,
		});
		if (!baseline || !initialBalances) {
			return;
		}

		const settlement = request(
			'POST',
			groupPath(data, 'settlements'),
			data.memberToken,
			{
				fromUserId: data.memberId,
				toUserId: data.ownerId,
				amount: '10.00',
				requestId: uuid(),
				note: 'Phase 5 smoke settlement',
			},
			'settlement_create',
			'write',
			[201],
		);
		const settlementBody = expectStatus(settlement, 201, 'Smoke settlement write');
		const persistedSettlements = get(groupPath(data, 'settlements'), data.ownerToken, 'settlements_list');
		check(persistedSettlements, {
			'settlement write persisted on read-back': (items) => Array.isArray(items)
				&& items.length === 1
				&& items[0].id === settlementBody.id
				&& minorUnits(items[0].amount) === 1000,
		});
		const settledBalances = get(groupPath(data, 'balances'), data.ownerToken, 'balances');
		check(settledBalances, {
			'settlement updates member balances correctly': (value) => {
				const owner = value.balances.find((member) => member.userId === data.ownerId);
				const debtor = value.balances.find((member) => member.userId === data.memberId);
				return owner && debtor
					&& minorUnits(owner.netBalance) === 4000
					&& minorUnits(debtor.netBalance) === -4000;
			},
		});
		return;
	}

	if (scenario === 'read') {
		const token = __VU % 2 === 0 ? data.ownerToken : data.memberToken;
		get('/api/groups', token, 'groups_list');
		get(groupPath(data, 'expenses'), token, 'expenses_list');
		get(groupPath(data, 'balances'), token, 'balances');
		get(groupPath(data, 'settlements'), token, 'settlements_list');
		get(groupPath(data, 'settlement-suggestions'), token, 'settlement_suggestions');
		sleep(0.4);
		return;
	}

	if (scenario === 'mixed') {
		const ownerPays = __VU % 2 === 0;
		const token = ownerPays ? data.ownerToken : data.memberToken;
		const payerId = ownerPays ? data.ownerId : data.memberId;
		get(groupPath(data, 'balances'), token, 'balances');
		const amount = (10 + ((__ITER + __VU) % 40)).toFixed(2);
		createExpense(data, token, payerId, `Phase 5 mixed ${__VU}-${__ITER}`, amount);
		sleep(0.4);
		return;
	}

	if (scenario === 'contention') {
		const response = request(
			'POST',
			groupPath(data, 'settlements'),
			data.memberToken,
			{
				fromUserId: data.memberId,
				toUserId: data.ownerId,
				amount: '5.00',
				requestId: data.contentionRequestId,
				note: 'Phase 5 controlled idempotency contention',
			},
			'settlement_create',
			'write',
			[201, 409],
		);
		if (response.status === 201) {
			createdSettlements.add(1);
		} else if (response.status === 409) {
			expectedSettlementConflicts.add(1);
		}
		return;
	}
}
