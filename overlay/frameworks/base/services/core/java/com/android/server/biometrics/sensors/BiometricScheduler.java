     /**
     * @return A reference to the internal callback that should be invoked whenever the scheduler
     *         needs to (e.g. client started, client finished).
     */
    @NonNull protected InternalCallback getInternalCallback() {
        return mInternalCallback;
    }

    protected String getTag() {
        return BASE_TAG + "/" + mBiometricTag;
    }

    protected void startNextOperationIfIdle() {
        if (mCurrentOperation != null) {
            Slog.v(getTag(), "Not idle, cancelling current operation: " + mCurrentOperation);
            cancelInternal(mCurrentOperation);
        }
        if (mPendingOperations.isEmpty()) {
            Slog.d(getTag(), "No operations, returning to idle");
            return;
        }

        mCurrentOperation = mPendingOperations.poll();
        final BaseClientMonitor currentClient = mCurrentOperation.mClientMonitor;
        Slog.d(getTag(), "[Polled] " + mCurrentOperation);

        // If the operation at the front of the queue has been marked for cancellation, send
        // ERROR_CANCELED. No need to start this client.
        if (mCurrentOperation.mState == Operation.STATE_WAITING_IN_QUEUE_CANCELING) {
            Slog.d(getTag(), "[Now Cancelling] " + mCurrentOperation);
            if (!(currentClient instanceof Interruptable)) {
                throw new IllegalStateException("Mis-implemented client or scheduler, "
                        + "trying to cancel non-interruptable operation: " + mCurrentOperation);
            }

            final Interruptable interruptable = (Interruptable) currentClient;
            interruptable.cancelWithoutStarting(getInternalCallback());
            // Now we wait for the client to send its FinishCallback, which kicks off the next
            // operation.
            return;
        }

        if (mGestureAvailabilityDispatcher != null
                && mCurrentOperation.mClientMonitor instanceof AcquisitionClient) {
            mGestureAvailabilityDispatcher.markSensorActive(
                    mCurrentOperation.mClientMonitor.getSensorId(),
                    true /* active */);
        }

        // Not all operations start immediately. BiometricPrompt waits for its operation
        // to arrive at the head of the queue, before pinging it to start.
        final boolean shouldStartNow = currentClient.getCookie() == 0;
        if (shouldStartNow) {
            if (mCurrentOperation.isUnstartableHalOperation()) {
                final HalClientMonitor<?> halClientMonitor =
                        (HalClientMonitor<?>) mCurrentOperation.mClientMonitor;
                // Note down current length of queue
                final int pendingOperationsLength = mPendingOperations.size();
                final Operation lastOperation = mPendingOperations.peekLast();
                Slog.e(getTag(), "[Unable To Start] " + mCurrentOperation
                        + ". Last pending operation: " + lastOperation);

                // For current operations, 1) unableToStart, which notifies the caller-side, then
                // 2) notify operation's callback, to notify applicable system service that the
                // operation failed.
                halClientMonitor.unableToStart();
                if (mCurrentOperation.mClientCallback != null) {
                    mCurrentOperation.mClientCallback.onClientFinished(
                            mCurrentOperation.mClientMonitor, false /* success */);
                }

                // Then for each operation currently in the pending queue at the time of this
                // failure, do the same as above. Otherwise, it's possible that something like
                // setActiveUser fails, but then authenticate (for the wrong user) is invoked.
                for (int i = 0; i < pendingOperationsLength; i++) {
                    final Operation operation = mPendingOperations.pollFirst();
                    if (operation == null) {
                        Slog.e(getTag(), "Null operation, index: " + i
                                + ", expected length: " + pendingOperationsLength);
                        break;
                    }
                    if (operation.isHalOperation()) {
                        ((HalClientMonitor<?>) operation.mClientMonitor).unableToStart();
                    }
                    if (operation.mClientCallback != null) {
                        operation.mClientCallback.onClientFinished(operation.mClientMonitor,
                                false /* success */);
                    }
                    Slog.w(getTag(), "[Aborted Operation] " + operation);
                }

                // It's possible that during cleanup a new set of operations came in. We can try to
                // run these. A single request from the manager layer to the service layer may
                // actually be multiple operations (i.e. updateActiveUser + authenticate).
                mCurrentOperation = null;
                startNextOperationIfIdle();
            } else {
                Slog.d(getTag(), "[Starting] " + mCurrentOperation);
                currentClient.start(getInternalCallback());
                mCurrentOperation.mState = Operation.STATE_STARTED;
            }
        } else {
            try {
                mBiometricService.onReadyForAuthentication(currentClient.getCookie());
            } catch (RemoteException e) {
                Slog.e(getTag(), "Remote exception when contacting BiometricService", e);
            }
            Slog.d(getTag(), "Waiting for cookie before starting: " + mCurrentOperation);
            mCurrentOperation.mState = Operation.STATE_WAITING_FOR_COOKIE;
        }
    }
