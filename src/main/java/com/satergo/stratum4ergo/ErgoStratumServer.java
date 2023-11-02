package com.satergo.stratum4ergo;

import com.redbottledesign.bitcoin.rpc.stratum.transport.AbstractConnectionState;
import com.redbottledesign.bitcoin.rpc.stratum.transport.ConnectionState;
import com.redbottledesign.bitcoin.rpc.stratum.transport.StatefulMessageTransport;
import com.redbottledesign.bitcoin.rpc.stratum.transport.tcp.StratumTcpServer;
import com.redbottledesign.bitcoin.rpc.stratum.transport.tcp.StratumTcpServerConnection;
import com.satergo.stratum4ergo.counter.SubscriptionIdCounter;
import com.satergo.stratum4ergo.data.Options;
import com.satergo.stratum4ergo.message.Announcement;
import com.satergo.stratum4ergo.message.Requests;
import com.satergo.stratum4ergo.message.Response;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.HexFormat;

public class ErgoStratumServer extends StratumTcpServer {

	private final Options options;
	private final SubscriptionIdCounter subscriptionIdCounter = new SubscriptionIdCounter();
	private final Pool pool;

	public ErgoStratumServer(Options options) {
		this.options = options;
		pool = new Pool(options, this);
		try {
			pool.start();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	protected StratumTcpServerConnection createConnection(Socket connectionSocket) {
		return new StratumTcpServerConnection(this, connectionSocket) {
			@Override
			protected ConnectionState createPostConnectState() {
				return new ErgoConnectionState(ErgoStratumServer.this, this, subscriptionIdCounter.next(), (InetSocketAddress) connectionSocket.getRemoteSocketAddress());
			}
		};
	}

	@Override
	protected void acceptConnection(StratumTcpServerConnection connection) {
		getConnections().put(connection.getConnectionId(), connection);
	}

	public void broadcastMiningJob(BlockTemplate blockTemplate) {
		getConnections().asMap().forEach((k, v) -> v.sendResponse(Announcement.miningJob(blockTemplate)));
	}

	public static class ErgoConnectionState extends AbstractConnectionState {

		private String extraNonce1;

		public ErgoConnectionState(ErgoStratumServer server, StatefulMessageTransport transport, String subscriptionId, InetSocketAddress socketAddress) {
			super(transport);
			registerRequestHandler(Requests.Subscribe.NAME, Requests.Subscribe.class, m -> {
				System.out.println("Subscription request: " + m);
				try {
					extraNonce1 = server.pool.jobManager.extraNonceCounter.next();
					getTransport().sendResponse(Response.subscribe(m.getId(), subscriptionId, extraNonce1, 4));
					getTransport().sendResponse(Announcement.difficulty(new BigInteger("1")));
					getTransport().sendResponse(Announcement.miningJob(server.pool.jobManager.currentJob));
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
			registerRequestHandler(Requests.Authorize.NAME, Requests.Authorize.class, m -> {
				System.out.println("Authorization request: workerName=" + m.workerName + ", password=" + m.password + ". Authorized.");
				try {
					getTransport().sendResponse(Response.authorize(m.getId(), true, null));
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
			registerRequestHandler(Requests.Submit.NAME, Requests.Submit.class, m -> {
				try {
					if (extraNonce1 == null) {
						getTransport().sendResponse(Response.submit(m.getId(), null, "25: not subscribed"));
						return;
					}
					String name = (String) m.getParams().get(0);
					String jobId = (String) m.getParams().get(1);
					byte[] extraNonce2 = HexFormat.of().parseHex((String) m.getParams().get(2));
					String nTime = (String) m.getParams().get(3);
					server.pool.jobManager.processShare(jobId, new BigInteger("1"), HexFormat.of().parseHex(extraNonce1), extraNonce2, nTime, socketAddress.getHostString(), socketAddress.getPort(), name);
				} catch (IOException e) {
					throw new RuntimeException(e);
				} catch (JobManager.ProcessingException e) {
					e.printStackTrace();
				}
			});
		}
	}
}
