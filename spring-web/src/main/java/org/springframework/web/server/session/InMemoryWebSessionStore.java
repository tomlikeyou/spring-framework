/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.server.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import reactor.core.publisher.Mono;

import org.springframework.util.Assert;
import org.springframework.util.IdGenerator;
import org.springframework.util.JdkIdGenerator;
import org.springframework.web.server.WebSession;

/**
 * Simple Map-based storage for {@link WebSession} instances.
 *
 * @author Rossen Stoyanchev
 * @author Rob Winch
 * @since 5.0
 */
public class InMemoryWebSessionStore implements WebSessionStore {

	private static final IdGenerator idGenerator = new JdkIdGenerator();


	private Clock clock = Clock.system(ZoneId.of("GMT"));

	private final ConcurrentMap<String, InMemoryWebSession> sessions = new ConcurrentHashMap<>();

	private final ExpiredSessionChecker expiredSessionChecker = new ExpiredSessionChecker();


	/**
	 * Configure the {@link Clock} to use to set lastAccessTime on every created
	 * session and to calculate if it is expired.
	 * <p>This may be useful to align to different timezone or to set the clock
	 * back in a test, e.g. {@code Clock.offset(clock, Duration.ofMinutes(-31))}
	 * in order to simulate session expiration.
	 * <p>By default this is {@code Clock.system(ZoneId.of("GMT"))}.
	 * @param clock the clock to use
	 */
	public void setClock(Clock clock) {
		Assert.notNull(clock, "Clock is required");
		this.clock = clock;
		this.expiredSessionChecker.removeExpiredSessions(clock.instant());
	}

	/**
	 * Return the configured clock for session lastAccessTime calculations.
	 */
	public Clock getClock() {
		return this.clock;
	}


	@Override
	public Mono<WebSession> createWebSession() {
		Instant now = this.clock.instant();
		this.expiredSessionChecker.checkIfNecessary(now);
		return Mono.fromSupplier(() -> new InMemoryWebSession(now));
	}

	@Override
	public Mono<WebSession> retrieveSession(String id) {
		Instant now = this.clock.instant();
		this.expiredSessionChecker.checkIfNecessary(now);
		InMemoryWebSession session = this.sessions.get(id);
		if (session == null) {
			return Mono.empty();
		}
		else if (session.isExpired(now)) {
			this.sessions.remove(id);
			return Mono.empty();
		}
		else {
			session.updateLastAccessTime(now);
			return Mono.just(session);
		}
	}

	@Override
	public Mono<Void> removeSession(String id) {
		this.sessions.remove(id);
		return Mono.empty();
	}

	public Mono<WebSession> updateLastAccessTime(WebSession webSession) {
		return Mono.fromSupplier(() -> {
			Assert.isInstanceOf(InMemoryWebSession.class, webSession);
			InMemoryWebSession session = (InMemoryWebSession) webSession;
			session.updateLastAccessTime(getClock().instant());
			return session;
		});
	}


	private class InMemoryWebSession implements WebSession {

		private final AtomicReference<String> id = new AtomicReference<>(String.valueOf(idGenerator.generateId()));

		private final Map<String, Object> attributes = new ConcurrentHashMap<>();

		private final Instant creationTime;

		private volatile Instant lastAccessTime;

		private volatile Duration maxIdleTime = Duration.ofMinutes(30);

		private final AtomicReference<State> state = new AtomicReference<>(State.NEW);


		public InMemoryWebSession(Instant creationTime) {
			this.creationTime = creationTime;
			this.lastAccessTime = this.creationTime;
		}

		@Override
		public String getId() {
			return this.id.get();
		}

		@Override
		public Map<String, Object> getAttributes() {
			return this.attributes;
		}

		@Override
		public Instant getCreationTime() {
			return this.creationTime;
		}

		@Override
		public Instant getLastAccessTime() {
			return this.lastAccessTime;
		}

		@Override
		public void setMaxIdleTime(Duration maxIdleTime) {
			this.maxIdleTime = maxIdleTime;
		}

		@Override
		public Duration getMaxIdleTime() {
			return this.maxIdleTime;
		}

		@Override
		public void start() {
			this.state.compareAndSet(State.NEW, State.STARTED);
		}

		@Override
		public boolean isStarted() {
			return this.state.get().equals(State.STARTED) || !getAttributes().isEmpty();
		}

		@Override
		public Mono<Void> changeSessionId() {
			String currentId = this.id.get();
			InMemoryWebSessionStore.this.sessions.remove(currentId);
			String newId = String.valueOf(idGenerator.generateId());
			this.id.set(newId);
			InMemoryWebSessionStore.this.sessions.put(this.getId(), this);
			return Mono.empty();
		}

		@Override
		public Mono<Void> invalidate() {
			this.state.set(State.EXPIRED);
			getAttributes().clear();
			InMemoryWebSessionStore.this.sessions.remove(this.id.get());
			return Mono.empty();
		}

		@Override
		public Mono<Void> save() {
			if (!getAttributes().isEmpty()) {
				this.state.compareAndSet(State.NEW, State.STARTED);
			}
			InMemoryWebSessionStore.this.sessions.put(this.getId(), this);
			return Mono.empty();
		}

		@Override
		public boolean isExpired() {
			return isExpired(Instant.now(getClock()));
		}

		private boolean isExpired(Instant currentTime) {
			if (this.state.get().equals(State.EXPIRED)) {
				return true;
			}
			if (checkExpired(currentTime)) {
				this.state.set(State.EXPIRED);
				return true;
			}
			return false;
		}

		private boolean checkExpired(Instant currentTime) {
			return isStarted() && !this.maxIdleTime.isNegative() &&
					currentTime.minus(this.maxIdleTime).isAfter(this.lastAccessTime);
		}

		private void updateLastAccessTime(Instant currentTime) {
			this.lastAccessTime = currentTime;
		}
	}


	private class ExpiredSessionChecker {

		/** Max time before next expiration checks. */
		private static final int CHECK_PERIOD = 60;

		/** Max sessions that can be created before next expiration checks. */
		private static final int SESSION_COUNT_THRESHOLD = 500;


		private final ReentrantLock lock = new ReentrantLock();

		private Instant nextCheckTime = Instant.now(clock).plus(CHECK_PERIOD, ChronoUnit.SECONDS);

		private long lastSessionCount;


		public void checkIfNecessary(Instant now) {
			if (howManyCreated() > SESSION_COUNT_THRESHOLD || this.nextCheckTime.isBefore(now)) {
				removeExpiredSessions(Instant.now(clock));
			}
		}

		private long howManyCreated() {
			return sessions.size() - this.lastSessionCount;
		}

		public void removeExpiredSessions(Instant now) {
			if (sessions.isEmpty()) {
				return;
			}
			if (this.lock.tryLock()) {
				try {
					Iterator<InMemoryWebSession> iterator = sessions.values().iterator();
					while (iterator.hasNext()) {
						InMemoryWebSession session = iterator.next();
						if (session.isExpired(now)) {
							iterator.remove();
							session.invalidate();
						}
					}
				}
				finally {
					this.nextCheckTime = clock.instant().plus(CHECK_PERIOD, ChronoUnit.SECONDS);
					this.lastSessionCount = sessions.size();
					this.lock.unlock();
				}
			}
		}
	}


	private enum State { NEW, STARTED, EXPIRED }

}
