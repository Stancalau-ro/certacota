# Product Summary: Real-Time Token Economy Engine

## What It Is

A standalone, payment-rail-agnostic token management engine designed to handle the full complexity of real-time multi-party token economies. It manages token flow between any number of participants simultaneously — combining continuous rate-based streams and discrete one-off transactions against live balances — with real-time forward estimation of each participant's token position at any moment.

It is explicitly **not** a payment processor, not a crypto system, and not a billing platform. It sits between the payment layer and the application layer, concerned solely with token issuance, movement, accounting, and projection.

---

## Core Features

### 1. Concurrent Mixed Transaction Types on a Single Balance
A participant's balance is correctly maintained across simultaneously active transaction types without serialization or polling:
- **Streaming transactions** — continuous rate-based token drain or accumulation (e.g. a per-minute private show running for an unknown duration)
- **Discrete transactions** — instantaneous one-off movements (tips, top-ups, withdrawals, entry fees)

Both types operate concurrently against the same balance with full correctness guarantees. A user can be mid-way through a streaming session and receive a discrete top-up or send a one-off transfer to a third party — all resolved correctly in real time.

### 2. Multi-Party Session Transactions
A single session or event can involve N participants simultaneously, each with their own directional token flow:
- Multiple consumers draining at individual rates
- One or more providers accumulating from those consumers
- Platform rake extracted atomically from each transfer
- All flows linked to the same session context and resolved against live individual balances

There is no assumption that transactions are bilateral. A group session, a poker table, a multi-bidder auction — any topology of participants and flow directions is supported.

### 3. Real-Time Token Flow Estimation
For every active participant the system provides a forward projection of their token position given all currently in-flight transactions:
- "At current burn rate, this participant's balance reaches zero in T seconds"
- "At current earn rate, this participant will have accumulated X tokens in T seconds"
- Accounts for all concurrent active streams simultaneously, not just the most recent transaction

This is a predictive capability, not a balance lookup. It enables platforms to warn users before exhaustion, gracefully terminate sessions, display live countdowns, or make real-time eligibility decisions.

### 4. Configurable Rake Engine
Platform commission is a first-class feature, not an afterthought:
- Rake rate configurable per transaction type, resolved from caller-supplied metadata at the time of transfer
- Rake extraction is atomic with the transfer — a three-way split (from → to → platform) in a single operation with no intermediate inconsistent state
- Rake beneficiary is configurable — supports platform accounts, revenue-share splits, affiliate commissions
- Supports spread-only models (rake at 0%), pure rake models, or hybrid configurations

### 5. Session Awareness with Open Metadata
The engine understands that token flows occur within a session context and tracks them accordingly:
- Sessions and transactions carry an arbitrary caller-supplied parameter map — the engine imposes no schema on this metadata, it flows through and surfaces on all events and audit records
- Token flows are linked to session context for audit, dispute resolution, and analytics
- Session state transitions (start, pause, end) are coordinated with token flow lifecycle — a session ending cleanly stops all associated streaming transactions atomically
- Threshold events (configurable accumulation or drain targets) are detected and emitted as first-class events

### 6. Payment Rail Decoupling
The engine receives token issuance commands ("add 500 tokens to account X") and emits token redemption events ("remove 500 tokens from account Y") without any knowledge of or dependency on the underlying payment mechanism. Integration with payment processors, crypto rails, or internal accounting systems is the responsibility of the surrounding platform, not the engine.

---

## Use Cases

### Primary: Adult Live Cam Platforms
The domain the system was designed for and where all four core features are exercised simultaneously:
- Per-minute streaming sessions of unknown duration draining viewer balance in real time
- Group sessions with N concurrent paying viewers each draining at the same rate
- Discrete tip transactions where a token threshold triggers a platform event — detected exactly once even under concurrent tip storms
- Observer sessions charging one participant per minute without affecting other active streams
- Real-time balance estimation drives the "you have X minutes remaining" UI element
- Rake engine handles the platform's cut at the moment of transfer, keyed off caller-supplied metadata

### Secondary: Online Gambling and Poker Platforms
Structural match is nearly identical:
- A poker hand is a multi-party concurrent transaction with N players
- Side bets and tournament entries are discrete one-offs on the same chip balance
- Real-time estimation: projected time/hands remaining at current stake level
- Rake per pot is the standard poker room revenue model — directly supported
- Responsible gambling features (balance exhaustion warnings) enabled by the estimation layer

### Tertiary: Live Interactive Entertainment
Any platform where a host earns while multiple participants spend simultaneously:
- Live auction platforms (bidding drains balance, winning bid transfers to seller)
- Interactive game shows with audience participation
- Live sports tipping platforms
- Virtual events with tipping or pay-per-interaction mechanics
- Non-adult live streaming platforms wanting a more sophisticated token economy than simple one-off tips

### Emerging: Prepaid Resource Consumption Platforms
Where a resource is consumed continuously at a rate with discrete top-up events:
- GPU/compute rental platforms charging credits per minute of usage
- API platforms with prepaid credit balances consumed per request or per session
- Coworking and shared resource platforms with credit-based access
- Telecom MVNO prepaid airtime management (modern replacement for legacy IN infrastructure)

---

## What Makes It Distinct

Most ledger and billing systems are either:
- **Backward-looking** — they record what happened, not what is happening
- **Discrete-only** — they handle one-off transactions well but have no concept of a streaming rate-based drain
- **Bilateral** — they model A pays B, not A + B + C all transacting simultaneously within a shared session
- **Payment-coupled** — they conflate the token layer with the payment processing layer

This engine is forward-looking, handles mixed transaction types, is natively multi-party, and is cleanly decoupled from payment rails. The combination of all four in a single component, designed specifically for the correctness and latency requirements of real-time interactive sessions, has no direct off-the-shelf equivalent in the market.
