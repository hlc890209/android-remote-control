const WebSocket = require('ws');
const { v4: uuidv4 } = require('uuid');

const PORT = process.env.PORT || 8080;
const AUTH_TOKEN = process.env.AUTH_TOKEN || 'zyyh_remote_test_2024';

const wss = new WebSocket.Server({ port: PORT });
const rooms = new Map();

wss.on('connection', (ws, req) => {
    console.log(`[连接] ${req.socket.remoteAddress}`);
    let alive = true;

    ws.on('pong', () => { alive = true; });

    const interval = setInterval(() => {
        if (!alive) { ws.terminate(); return; }
        alive = false;
        ws.ping();
    }, 30000);

    ws.on('message', (data) => {
        try {
            const msg = JSON.parse(data.toString());
            switch (msg.type) {
                case 'auth': handleAuth(ws, msg); break;
                case 'register': handleRegister(ws, msg); break;
                case 'signal': handleSignal(ws, msg); break;
                default: sendError(ws, 'Unknown message type');
            }
        } catch (e) {
            sendError(ws, 'Invalid JSON');
        }
    });

    ws.on('close', () => {
        clearInterval(interval);
        handleDisconnect(ws);
    });

    ws.send(JSON.stringify({ type: 'welcome', message: 'Connected to ZYYH Remote Test Relay' }));
});

function handleAuth(ws, msg) {
    if (msg.token === AUTH_TOKEN) {
        ws.authenticated = true;
        ws.send(JSON.stringify({ type: 'auth_ok' }));
        console.log(`[认证] 成功: ${ws._socket?.remoteAddress}`);
    } else {
        ws.send(JSON.stringify({ type: 'auth_error', message: 'Invalid token' }));
        ws.close();
    }
}

function handleRegister(ws, msg) {
    if (!ws.authenticated) { sendError(ws, 'Not authenticated'); return; }
    const { role, roomId } = msg;
    if (!role || !roomId) { sendError(ws, 'Missing role or roomId'); return; }

    if (!rooms.has(roomId)) {
        rooms.set(roomId, { controller: null, controlled: null });
    }
    const room = rooms.get(roomId);
    if (role === 'controller') room.controller = ws;
    else if (role === 'controlled') room.controlled = ws;

    ws.roomId = roomId;
    ws.role = role;
    ws.send(JSON.stringify({ type: 'registered', role, roomId }));
    console.log(`[注册] ${role} -> 房间 ${roomId}`);

    if (room.controller && room.controlled) {
        const notify = JSON.stringify({ type: 'peer_connected', message: '双方已连接，可以开始操作' });
        room.controller.send(notify);
        room.controlled.send(notify);
        console.log(`[就绪] 房间 ${roomId} 双方已连接`);
    }
}

function handleSignal(ws, msg) {
    if (!ws.authenticated || !ws.roomId || !ws.role) { sendError(ws, 'Not registered'); return; }
    const room = rooms.get(ws.roomId);
    if (!room) return;

    const target = ws.role === 'controller' ? room.controlled : room.controller;
    if (target && target.readyState === WebSocket.OPEN) {
        msg.from = ws.role;
        target.send(JSON.stringify(msg));
    } else {
        sendError(ws, '对方不在线');
    }
}

function handleDisconnect(ws) {
    if (ws.roomId && rooms.has(ws.roomId)) {
        const room = rooms.get(ws.roomId);
        const other = ws.role === 'controller' ? room.controlled : room.controller;
        if (ws.role === 'controller') room.controller = null;
        else room.controlled = null;

        if (other && other.readyState === WebSocket.OPEN) {
            other.send(JSON.stringify({ type: 'peer_disconnected', message: '对方已断开连接' }));
        }
        if (!room.controller && !room.controlled) rooms.delete(ws.roomId);
        console.log(`[断开] ${ws.role} 离开房间 ${ws.roomId}`);
    }
}

function sendError(ws, message) {
    ws.send(JSON.stringify({ type: 'error', message }));
}

console.log(`======================================`);
console.log(`  ZYYH Remote Test Relay Server`);
console.log(`  端口: ${PORT}`);
console.log(`  Token: ${AUTH_TOKEN}`);
console.log(`======================================`);
