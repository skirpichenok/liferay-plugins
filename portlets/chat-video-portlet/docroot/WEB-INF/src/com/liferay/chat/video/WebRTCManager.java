/**
 * Copyright (c) 2000-2013 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.chat.video;

import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author Philippe Proulx
 */
public class WebRTCManager {

	public WebRTCManager() {
		_webRTCManagers.add(this);
	}

	public List<Long> getAvailableWebRTCClientIds() {
		List<Long> availableUserIds = new ArrayList<Long>();

		for (long userId : _webRTCClients.keySet()) {
			if (hasAvailableWebRTCClient(userId)) {
				availableUserIds.add(userId);
			}
		}

		return availableUserIds;
	}

	public WebRTCClient getWebRTCClient(long userId) {
		return _webRTCClients.get(userId);
	}

	public boolean hasAvailableWebRTCClient(long userId) {
		WebRTCClient webRTCClient = _webRTCClients.get(userId);

		if (webRTCClient == null) {
			return false;
		}

		return webRTCClient.isAvailable();
	}

	public void removeWebRTCClient(long userId) {
		WebRTCClient webRTCClient = getWebRTCClient(userId);

		if (webRTCClient == null) {
			return;
		}

		webRTCClient.removeBilateralWebRTCConnections();

		_webRTCClients.remove(userId);
	}

	public void updateWebRTCClientAvailability(long userId, boolean available) {
		addWebRTCClient(userId);

		WebRTCClient webRTCClient = getWebRTCClient(userId);

		webRTCClient.removeBilateralWebRTCConnections();

		webRTCClient.setAvailable(available);
	}

	public void updateWebRTCClientPresence(long userId) {
		WebRTCClient webRTCClient = getWebRTCClient(userId);

		if (webRTCClient == null) {
			return;
		}

		webRTCClient.updatePresenceTime();
	}

	protected void addWebRTCClient(long userId) {
		if (!_webRTCClients.containsKey(userId)) {
			_webRTCClients.put(userId, new WebRTCClient(userId));
		}
	}

	protected void checkWebRTCClients() {
		long time = System.currentTimeMillis();

		for (long userId : _webRTCClients.keySet()) {
			WebRTCClient webRTCClient = getWebRTCClient(userId);

			long presenceDurationTime = time - webRTCClient.getPresenceTime();

			if (presenceDurationTime > _PRESENCE_TIMEOUT_DURATION_TIME) {
				resetWebRTCClient(userId);

				removeWebRTCClient(userId);
			}
		}
	}

	protected void checkWebRTCConnectionsStates() {
		for (WebRTCClient webRTCClient : _webRTCClients.values()) {
			for (WebRTCClient otherWebRTCClient :
					webRTCClient.getWebRTCClients()) {

				WebRTCConnection webRTCConnection =
					webRTCClient.getWebRTCConnection(otherWebRTCClient);

				if (webRTCConnection.getState() !=
						WebRTCConnection.State.INITIATED) {

					continue;
				}

				long initiatedDurationTime =
					webRTCConnection.getInitiatedDurationTime();

				if (initiatedDurationTime <=
						_CONNECTION_TIMEOUT_DURATION_TIME) {

					continue;
				}

				webRTCClient.removeBilateralWebRTCConnection(otherWebRTCClient);

				JSONObject messageJSONObject =
					JSONFactoryUtil.createJSONObject();

				messageJSONObject.put("reason", "timeout");
				messageJSONObject.put("status", "lost");
				messageJSONObject.put("type", "status");

				pushConnectionStateWebRTCMail(
					webRTCClient, otherWebRTCClient, messageJSONObject);

				pushConnectionStateWebRTCMail(
					otherWebRTCClient, webRTCClient, messageJSONObject);
			}
		}
	}

	protected boolean isValidWebRTCConnectionState(
		WebRTCClient webRTCClient1, WebRTCClient webRTCClient2,
		WebRTCConnection.State state) {

		WebRTCConnection webRTCClient1TowebRTCClient2WebRTCConnection =
			webRTCClient1.getWebRTCConnection(webRTCClient2);

		if (webRTCClient1TowebRTCClient2WebRTCConnection == null) {
			return false;
		}

		WebRTCConnection webRTCClient2TowebRTCClient1WebRTCConnection =
			webRTCClient2.getWebRTCConnection(webRTCClient1);

		if (webRTCClient2TowebRTCClient1WebRTCConnection == null) {
			return false;
		}

		if (webRTCClient1TowebRTCClient2WebRTCConnection !=
				webRTCClient2TowebRTCClient1WebRTCConnection) {

			return false;
		}

		if (webRTCClient1TowebRTCClient2WebRTCConnection.getState() != state) {
			return false;
		}

		return true;
	}

	protected void pushConnectionStateWebRTCMail(
		WebRTCClient sourceWebRTCClient, WebRTCClient destinationWebRTCClient,
		JSONObject messageJSONObject) {

		ConnectionStateWebRTCMail connectionStateWebRTCMail =
			new ConnectionStateWebRTCMail(
				sourceWebRTCClient.getUserId(), messageJSONObject);

		WebRTCMailbox destinationWebRTCMailbox =
			destinationWebRTCClient.getOutgoingWebRTCMailbox();

		destinationWebRTCMailbox.pushWebRTCMail(connectionStateWebRTCMail);
	}

	protected void resetWebRTCClient(long userId) {
		WebRTCClient webRTCClient = getWebRTCClient(userId);

		if (webRTCClient == null) {
			return;
		}

		Set<WebRTCClient> webRTCClients = webRTCClient.getWebRTCClients();

		for (WebRTCClient otherWebRTCClient : webRTCClients) {
			WebRTCConnection webRTCConnection =
				webRTCClient.getWebRTCConnection(webRTCClient);

			WebRTCConnection.State state = webRTCConnection.getState();

			if (state != WebRTCConnection.State.DISCONNECTED) {
				JSONObject messageJSONObject =
					JSONFactoryUtil.createJSONObject();

				messageJSONObject.put("reason", "reset");
				messageJSONObject.put("status", "lost");
				messageJSONObject.put("type", "status");

				pushConnectionStateWebRTCMail(
					webRTCClient, otherWebRTCClient, messageJSONObject);
			}
		}

		webRTCClient.reset();

		webRTCClient.updatePresenceTime();
	}

	private static long _CONNECTION_TIMEOUT_DURATION_TIME = 60000;

	private static long _PRESENCE_TIMEOUT_DURATION_TIME = 30000;

	private static List<WebRTCManager> _webRTCManagers =
		new CopyOnWriteArrayList<WebRTCManager>();

	private Map<Long, WebRTCClient> _webRTCClients =
		new ConcurrentHashMap<Long, WebRTCClient>();

}