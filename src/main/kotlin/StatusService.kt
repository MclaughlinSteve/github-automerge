import com.fasterxml.jackson.module.kotlin.readValue
import com.github.kittinunf.result.Result

/**
 * Service for performing actions related to github statuses.
 */
class StatusService(private val config: GithubConfig) {
    private val baseUrl = config.baseUrl
    private val headers = config.headers
    private val http = Http(headers)

    /**
     * Checks to see whether there are any outstanding status requests (things like travis builds for example)
     *
     * If the merge status is "BLOCKED" and there are no outstanding status checks, something else is causing
     * the branch to be unable to be merged (Either merge conflicts, or requested changes) and the label will
     * be removed
     *
     * @param pull the pull request for which the statuses are being determined
     */
    fun assessStatusAndChecks(pull: Pull) {
        getRequiredStatusAndChecks(pull)?.let {
            if (it.isEmpty()) {
                removeLabels(pull, LabelRemovalReason.OUTSTANDING_REVIEWS)
            } else {
                val statusCheck = getStatusOrChecks<Check, StatusCheck>(pull, SummaryType.CHECK_RUNS)
                val status = getStatusOrChecks<Status, StatusItem>(pull, SummaryType.STATUS)
                when {
                    statusCheck == null -> Unit
                    status == null -> Unit
                    else -> handleCompletedStatuses(pull, it, statusCheck, status)
                }
            }
        }
    }

    private fun handleCompletedStatuses(
            pull: Pull,
            required: List<String>,
            statusCheck: Map<String, StatusCheck>,
            status: Map<String, StatusItem>
    ) {
        val statusMap = required.map { nameToStatusState(it, statusCheck, status) }.toMap()

        when {
            statusMap.values.all { it == StatusState.SUCCESS } -> removeLabels(pull, LabelRemovalReason.OUTSTANDING_REVIEWS)
            statusMap.containsValue(StatusState.FAILURE) -> removeLabels(pull, LabelRemovalReason.STATUS_CHECKS)
            else -> Unit
        }
    }

    /**
     * Given a name and a list of checks and statuses, determine the appropriate status state for each name
     *
     * @param name the name of the status
     * @param statusCheck a mapping of names to checks
     * @param status a mapping of names to statuses
     * @return a pair containing the given status name and the determined status state for that status name
     */
    private fun nameToStatusState(
        name: String,
        statusCheck: Map<String, StatusCheck>,
        status: Map<String, StatusItem>
    ): Pair<String, StatusState> {
        return name to when (name) {
            in statusCheck -> checkState(statusCheck.getValue(name))
            in status -> statusState(status.getValue(name))
            else -> StatusState.PENDING
        }
    }

    /**
     * Gets a list of the required status and checks for the branch being merged into
     *
     * @param pull the pull request being evaluated. It will contain the branch being merged into
     */
    private fun getRequiredStatusAndChecks(pull: Pull): List<String>? {
        val url = "$baseUrl/$BRANCHES/${pull.base.ref}"
        val (_, _, result) = http.get(url)
        return when (result) {
            is Result.Failure -> {
                logFailure(result, "There was a problem getting the branch protections")
                null
            }
            is Result.Success -> {
                val branchDetails = mapper.readValue<BranchDetails>(result.get())
                if (!branchDetails.protected) {
                    emptyList()
                } else {
                    branchDetails.protection.requiredStatusChecks.contexts
                }
            }
        }
    }

    /**
     * Get the status summary or "check-runs" summary for a pull request
     *
     * @param pull the pull request to get the status or "check-runs" for
     * @param summaryType the type that we're getting (Status or Check_runs)
     * @return a mapping of status names to the associated status or check_run
     */
    private inline fun <reified StatusOrCheck, reified StatusResponse> getStatusOrChecks(
        pull: Pull,
        summaryType: SummaryType
    ): Map<String, StatusResponse>? {
        val url = "$baseUrl/$COMMITS/${pull.head.sha}/${summaryType.route}"
        val (_, _, result) = http.get(url)
        return when (result) {
            is Result.Failure -> {
                logFailure(result)
                null
            }
            is Result.Success -> {
                val statusOrCheck = mapper.readValue<StatusOrCheck>(result.get())
                nameToStatusInfo(statusOrCheck)
            }
        }
    }

    /**
     * Get a mapping of the status names to the associated status or check
     *
     * @param statusOrCheck the status or check from github to map to status names
     * @return a mapping of status names to the related status data
     */
    private inline fun <reified StatusOrCheck, reified StatusResponse> nameToStatusInfo(
        statusOrCheck: StatusOrCheck
    ): Map<String, StatusResponse>? =
        when (statusOrCheck) {
            is Status -> statusOrCheck.statuses.map { it.context to it as StatusResponse }.toMap()
            is Check -> statusOrCheck.checkRuns.map { it.name to it as StatusResponse }.toMap()
            else -> null
        }

    /**
     * Determine the state of the check
     *
     * @param item the check-run provided by github
     * @return the state of the check
     */
    private fun checkState(item: StatusCheck): StatusState {
        val failureStates = listOf("failure", "action_required", "cancelled", "timed_out")
        return when {
            failureStates.contains(item.conclusion) -> StatusState.FAILURE
            item.status == "completed" -> StatusState.SUCCESS
            else -> StatusState.PENDING
        }
    }

    /**
     * Determine the state of the status
     *
     * @param item the status provided by github
     * @return the state of the status
     */
    private fun statusState(item: StatusItem): StatusState {
        return when (item.state) {
            "failure" -> StatusState.FAILURE
            "error" -> StatusState.FAILURE
            "pending" -> StatusState.PENDING
            else -> StatusState.SUCCESS
        }
    }

    /**
     * Removes the Automerge and Priority labels from a pull request if they exist
     *
     * @param pull the pull request for which the label will be removed
     * @param reason some information about why the label is removed which will be commented on the PR
     */
    private fun removeLabels(pull: Pull, reason: LabelRemovalReason) = LabelService(config).removeLabels(pull, reason)
}
