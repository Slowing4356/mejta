package com.mejta.mejtadiscord.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** A tiny scripted WebSocket server (RFC 6455, text frames only) standing in for Discord's gateway. */
final class FakeGateway implements AutoCloseable {
	private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

	private final ServerSocket server;
	private final BlockingQueue<Conn> connections = new LinkedBlockingQueue<>();
	private final Thread acceptor;

	FakeGateway() throws IOException {
		server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
		acceptor = new Thread(this::acceptLoop, "FakeGateway-accept");
		acceptor.setDaemon(true);
		acceptor.start();
	}

	URI uri() {
		return URI.create("ws://127.0.0.1:" + server.getLocalPort());
	}

	/** The next connection the client opened, after its handshake. */
	Conn next(long timeoutMs) throws InterruptedException {
		return connections.poll(timeoutMs, TimeUnit.MILLISECONDS);
	}

	private void acceptLoop() {
		while (!server.isClosed()) {
			try {
				Socket socket = server.accept();
				Conn conn = new Conn(socket);
				connections.add(conn);
			} catch (IOException e) {
				return;
			}
		}
	}

	@Override
	public void close() throws IOException {
		server.close();
	}

	static final class Conn {
		final Socket socket;
		final String path;
		final BlockingQueue<JsonObject> received = new LinkedBlockingQueue<>();
		volatile int closeCode = -1;
		private final InputStream in;
		private final OutputStream out;

		Conn(Socket socket) throws IOException {
			this.socket = socket;
			this.in = socket.getInputStream();
			this.out = socket.getOutputStream();
			Map<String, String> headers = new HashMap<>();
			String requestLine = readLine();
			String line;
			while (!(line = readLine()).isEmpty()) {
				int colon = line.indexOf(':');
				if (colon > 0) {
					headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
				}
			}
			this.path = requestLine.split(" ")[1];
			String accept = accept(headers.getOrDefault("sec-websocket-key", ""));
			out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept
					+ "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			out.flush();
			Thread reader = new Thread(this::readLoop, "FakeGateway-read");
			reader.setDaemon(true);
			reader.start();
		}

		private static String accept(String key) {
			try {
				byte[] sha = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
				return Base64.getEncoder().encodeToString(sha);
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			}
		}

		private String readLine() throws IOException {
			ByteArrayOutputStream line = new ByteArrayOutputStream();
			int b;
			while ((b = in.read()) != -1) {
				if (b == '\n') {
					break;
				}
				if (b != '\r') {
					line.write(b);
				}
			}
			return line.toString(StandardCharsets.US_ASCII);
		}

		private void readLoop() {
			ByteArrayOutputStream message = new ByteArrayOutputStream();
			try {
				while (true) {
					int b0 = in.read();
					int b1 = in.read();
					if (b0 < 0 || b1 < 0) {
						return;
					}
					int opcode = b0 & 0x0F;
					boolean fin = (b0 & 0x80) != 0;
					long length = b1 & 0x7F;
					if (length == 126) {
						length = (in.read() << 8) | in.read();
					} else if (length == 127) {
						length = 0;
						for (int i = 0; i < 8; i++) {
							length = (length << 8) | in.read();
						}
					}
					byte[] mask = (b1 & 0x80) != 0 ? in.readNBytes(4) : null;
					byte[] payload = in.readNBytes((int) length);
					if (mask != null) {
						for (int i = 0; i < payload.length; i++) {
							payload[i] ^= mask[i % 4];
						}
					}
					switch (opcode) {
						case 0x0, 0x1 -> {
							message.write(payload);
							if (fin) {
								JsonElement json = JsonParser.parseString(message.toString(StandardCharsets.UTF_8));
								message.reset();
								received.add(json.getAsJsonObject());
							}
						}
						case 0x8 -> {
							closeCode = payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1005;
							synchronized (this) {
								try {
									frame(0x88, payload.length >= 2 ? new byte[] { payload[0], payload[1] } : new byte[0]);
								} catch (IOException ignored) {
									// already gone
								}
							}
							abort();
							return;
						}
						case 0x9 -> {
							synchronized (this) {
								frame(0x8A, payload);
							}
						}
						default -> {
						}
					}
				}
			} catch (IOException e) {
				// connection gone
			}
		}

		private void frame(int first, byte[] payload) throws IOException {
			ByteArrayOutputStream frame = new ByteArrayOutputStream();
			frame.write(first);
			if (payload.length < 126) {
				frame.write(payload.length);
			} else if (payload.length < 65536) {
				frame.write(126);
				frame.write(payload.length >> 8);
				frame.write(payload.length & 0xFF);
			} else {
				frame.write(127);
				for (int i = 7; i >= 0; i--) {
					frame.write((int) ((long) payload.length >> (8 * i)) & 0xFF);
				}
			}
			frame.write(payload);
			out.write(frame.toByteArray());
			out.flush();
		}

		synchronized void send(JsonObject payload) throws IOException {
			frame(0x81, payload.toString().getBytes(StandardCharsets.UTF_8));
		}

		void op(int op, JsonElement d) throws IOException {
			JsonObject payload = new JsonObject();
			payload.addProperty("op", op);
			payload.add("d", d);
			send(payload);
		}

		void hello(long intervalMs) throws IOException {
			JsonObject d = new JsonObject();
			d.addProperty("heartbeat_interval", intervalMs);
			op(10, d);
		}

		void dispatch(String type, int seq, JsonObject d) throws IOException {
			JsonObject payload = new JsonObject();
			payload.addProperty("op", 0);
			payload.addProperty("t", type);
			payload.addProperty("s", seq);
			payload.add("d", d);
			send(payload);
		}

		/** A close frame with this code, then the socket is closed. */
		synchronized void close(int code) throws IOException {
			frame(0x88, new byte[] { (byte) (code >> 8), (byte) code });
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			abort();
		}

		/** Drops the TCP connection with no close frame. */
		void abort() {
			try {
				socket.close();
			} catch (IOException ignored) {
				// already closed
			}
		}

		/** The next received payload with this op code; others (such as heartbeats) are skipped. */
		JsonObject expect(int op, long timeoutMs) throws InterruptedException {
			long end = System.currentTimeMillis() + timeoutMs;
			while (true) {
				long left = end - System.currentTimeMillis();
				JsonObject next = left <= 0 ? null : received.poll(left, TimeUnit.MILLISECONDS);
				if (next == null) {
					return null;
				}
				if (next.get("op").getAsInt() == op) {
					return next;
				}
			}
		}
	}
}
