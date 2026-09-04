param(
    [string]$BaseUrl = "http://127.0.0.1:8080"
)

$ErrorActionPreference = "Stop"
$adminKey = (Get-Content -Raw (Join-Path $PSScriptRoot "..\.secrets\finance_admin_key")).Trim()
$suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()

$ready = $false
1..30 | ForEach-Object {
    if ($ready) { return }
    try {
        $health = Invoke-RestMethod -Uri "$BaseUrl/health/ready" -TimeoutSec 2
        $ready = $health.status -eq "ready"
    } catch {
        Start-Sleep -Milliseconds 500
    }
}
if (-not $ready) { throw "Finance API did not become ready at $BaseUrl" }

function Invoke-JsonPost {
    param([string]$Path, [hashtable]$Body, [hashtable]$Headers = @{})
    Invoke-RestMethod -Method Post -Uri ($BaseUrl + $Path) -ContentType "application/json" `
        -Headers $Headers -Body ($Body | ConvertTo-Json -Depth 12 -Compress)
}

function Invoke-JsonDelete {
    param([string]$Path, [hashtable]$Body, [hashtable]$Headers = @{})
    Invoke-RestMethod -Method Delete -Uri ($BaseUrl + $Path) -ContentType "application/json" `
        -Headers $Headers -Body ($Body | ConvertTo-Json -Depth 12 -Compress)
}

$inviteA = Invoke-JsonPost "/v1/admin/registration-invites" @{ expiresInHours = 1; maxUses = 1 } @{ "X-Admin-Key" = $adminKey }
$inviteB = Invoke-JsonPost "/v1/admin/registration-invites" @{ expiresInHours = 1; maxUses = 1 } @{ "X-Admin-Key" = $adminKey }
$availabilityBefore = Invoke-RestMethod -Uri "$BaseUrl/v1/auth/username-availability?username=alice_$suffix"
$userA = Invoke-JsonPost "/v1/auth/register" @{
    inviteCode = $inviteA.code; username = "alice_$suffix"; displayName = "Alice"
    password = "correct-horse-42"; deviceName = "Integration A"
}
$userB = Invoke-JsonPost "/v1/auth/register" @{
    inviteCode = $inviteB.code; username = "bob_$suffix"; displayName = "Bob"
    password = "correct-horse-84"; deviceName = "Integration B"
}
$headersA = @{ Authorization = "Bearer $($userA.accessToken)" }
$headersB = @{ Authorization = "Bearer $($userB.accessToken)" }
$availabilityAfter = Invoke-RestMethod -Uri "$BaseUrl/v1/auth/username-availability?username=alice_$suffix"
$userCreatedInvite = Invoke-JsonPost "/v1/registration-invites" @{ expiresInHours = 1 } $headersA
$userC = Invoke-JsonPost "/v1/auth/register" @{
    inviteCode = $userCreatedInvite.code; username = "charlie_$suffix"; displayName = "Charlie"
    password = "correct-horse-96"; deviceName = "Integration C"
}
$headersC = @{ Authorization = "Bearer $($userC.accessToken)" }
$groupId = [guid]::NewGuid().ToString()
$createOperationId = [guid]::NewGuid().ToString()
$groupPayload = @{ id = $groupId; name = "Integration Trip"; members = @("Alice"); expenses = @(); settlements = @() }
$groupOperation = @{
    operationId = $createOperationId; entityType = "GROUP"; entityId = $groupId; action = "UPSERT"
    scopeType = "GROUP"; scopeId = $groupId; payload = $groupPayload
}
$firstPush = Invoke-JsonPost "/v1/sync/push" @{ operations = @($groupOperation) } $headersA
$duplicatePush = Invoke-JsonPost "/v1/sync/push" @{ operations = @($groupOperation) } $headersA
$outsiderClaim = Invoke-JsonPost "/v1/sync/push" @{ operations = @(@{
    operationId = [guid]::NewGuid().ToString(); entityType = "GROUP"; entityId = $groupId; action = "UPSERT"
    scopeType = "GROUP"; scopeId = $groupId; payload = $groupPayload
}) } $headersB
$groupInvite = Invoke-JsonPost "/v1/groups/$groupId/invites" @{ expiresInHours = 1 } $headersA
$accepted = Invoke-JsonPost "/v1/group-invites/accept" @{ code = $groupInvite.code } $headersB
$privateGroupId = [guid]::NewGuid().ToString()
$privateGroupPush = Invoke-JsonPost "/v1/sync/push" @{ operations = @(@{
    operationId = [guid]::NewGuid().ToString(); entityType = "GROUP"; entityId = $privateGroupId; action = "UPSERT"
    scopeType = "GROUP"; scopeId = $privateGroupId; payload = @{ id = $privateGroupId; name = "Alice private group"; members = @("Alice"); memberAccounts = @(); expenses = @(); settlements = @() }
}) } $headersA
$memberRename = Invoke-JsonPost "/v1/sync/push" @{ operations = @(@{
    operationId = [guid]::NewGuid().ToString(); entityType = "GROUP"; entityId = $groupId; action = "UPSERT"
    scopeType = "GROUP"; scopeId = $groupId; payload = @{ id = $groupId; name = "Hijacked"; members = @("Bob") }
}) } $headersB

$expenseId = [guid]::NewGuid().ToString()
$expensePush = Invoke-JsonPost "/v1/sync/push" @{ operations = @(@{
    operationId = [guid]::NewGuid().ToString(); entityType = "GROUP_EXPENSE"; entityId = $expenseId
    action = "UPSERT"; scopeType = "GROUP"; scopeId = $groupId
    payload = @{ id = $expenseId; title = "Dinner"; amountMinor = 4200; paidBy = "Bob"
        participants = @("Alice", "Bob"); epochDay = 20673; note = "Integration test" }
}) } $headersB
$personalId = [guid]::NewGuid().ToString()
$accountId = [guid]::NewGuid().ToString()
$accountPush = Invoke-JsonPost "/v1/sync/push" @{ operations = @(@{
    operationId = [guid]::NewGuid().ToString(); entityType = "FINANCIAL_ACCOUNT"; entityId = $accountId
    action = "UPSERT"; scopeType = "PERSONAL"; scopeId = "SELF"
    payload = @{ id = $accountId; name = "Integration Bank"; type = "BANK"; active = $true
        openingBalanceMinor = 0; nature = "ASSET"; liquidity = "LIQUID" }
}) } $headersA
$personalPush = Invoke-JsonPost "/v1/sync/push" @{ operations = @(@{
    operationId = [guid]::NewGuid().ToString(); entityType = "TRANSACTION"; entityId = $personalId
    action = "UPSERT"; scopeType = "PERSONAL"; scopeId = "SELF"
    payload = @{ id = $personalId; amountMinor = 1234; type = "EXPENSE"; category = "Other"
        account = $accountId; epochDay = 20673; note = "Private integration row" }
}) } $headersA
$loanId = [guid]::NewGuid().ToString()
$loanPaymentId = [guid]::NewGuid().ToString()
$loanPush = Invoke-JsonPost "/v1/sync/push" @{ operations = @(
    @{
        operationId = [guid]::NewGuid().ToString(); entityType = "LOAN"; entityId = $loanId
        action = "UPSERT"; scopeType = "PERSONAL"; scopeId = "SELF"
        payload = @{ id = $loanId; counterparty = "Integration Bank"; direction = "BORROWED"; principalMinor = 5000
            account = $accountId; startEpochDay = 20673; planType = "EMI"; annualInterestBps = 1200
            termMonths = 12; dueDay = 1; monthlyPaymentMinor = 0; note = "Private integration loan" }
    },
    @{
        operationId = [guid]::NewGuid().ToString(); entityType = "LOAN_PAYMENT"; entityId = $loanPaymentId
        action = "UPSERT"; scopeType = "PERSONAL"; scopeId = "SELF"
        payload = @{ id = $loanPaymentId; loanId = $loanId; amountMinor = 500; principalMinor = 450
            interestMinor = 50; account = $accountId; epochDay = 20674; note = "Private integration repayment" }
    }
) } $headersA
$pullA = Invoke-RestMethod -Uri "$BaseUrl/v1/sync/pull?after=0&limit=200" -Headers $headersA
$pullB = Invoke-RestMethod -Uri "$BaseUrl/v1/sync/pull?after=0&limit=200" -Headers $headersB
$unauthenticated = Invoke-WebRequest -Uri "$BaseUrl/v1/sync/pull?after=0" -SkipHttpErrorCheck
$refreshed = Invoke-JsonPost "/v1/auth/refresh" @{ refreshToken = $userA.refreshToken }
$oldRefreshReuse = Invoke-WebRequest -Method Post -Uri "$BaseUrl/v1/auth/refresh" -ContentType "application/json" `
    -Body (@{ refreshToken = $userA.refreshToken } | ConvertTo-Json -Compress) -SkipHttpErrorCheck
$invalidPayload = Invoke-WebRequest -Method Post -Uri "$BaseUrl/v1/sync/push" -Headers $headersB -ContentType "application/json" `
    -Body (@{ operations = @(@{ operationId = [guid]::NewGuid().ToString(); entityType = "TRANSACTION"; entityId = [guid]::NewGuid().ToString()
        action = "UPSERT"; scopeType = "PERSONAL"; scopeId = "SELF"; payload = @{ id = [guid]::NewGuid().ToString(); amountMinor = -1
            type = "EXPENSE"; category = "Other"; account = "Cash"; epochDay = 20673; note = "Invalid" } }) } | ConvertTo-Json -Depth 10 -Compress) -SkipHttpErrorCheck

$dashboardSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-RestMethod -Method Post -Uri "$BaseUrl/v1/dashboard/admin/login" -WebSession $dashboardSession -ContentType "application/json" -Body (@{ adminKey = $adminKey } | ConvertTo-Json -Compress) | Out-Null
$disabledSummary = Invoke-RestMethod -Method Post -Uri "$BaseUrl/v1/dashboard/admin/users/bob_$suffix/status" -WebSession $dashboardSession -ContentType "application/json" -Body (@{ enabled = $false } | ConvertTo-Json -Compress)
$disabledLogin = Invoke-WebRequest -Method Post -Uri "$BaseUrl/v1/auth/login" -ContentType "application/json" -Body (@{ username = "bob_$suffix"; password = "correct-horse-84"; deviceName = "Disabled check" } | ConvertTo-Json -Compress) -SkipHttpErrorCheck
$restoredSummary = Invoke-RestMethod -Method Post -Uri "$BaseUrl/v1/dashboard/admin/users/bob_$suffix/status" -WebSession $dashboardSession -ContentType "application/json" -Body (@{ enabled = $true } | ConvertTo-Json -Compress)
$restoredLogin = Invoke-JsonPost "/v1/auth/login" @{ username = "bob_$suffix"; password = "correct-horse-84"; deviceName = "Restored check" }

$trashResponse = Invoke-JsonDelete "/v1/me/account" @{ username = "charlie_$suffix"; password = "correct-horse-96"; mode = "TRASH" } $headersC
$trashedLogin = Invoke-WebRequest -Method Post -Uri "$BaseUrl/v1/auth/login" -ContentType "application/json" -Body (@{ username = "charlie_$suffix"; password = "correct-horse-96"; deviceName = "Trash check" } | ConvertTo-Json -Compress) -SkipHttpErrorCheck
$trashSummary = Invoke-RestMethod -Uri "$BaseUrl/v1/dashboard/admin/summary" -WebSession $dashboardSession
$restoreTrashSummary = Invoke-RestMethod -Method Post -Uri "$BaseUrl/v1/dashboard/admin/users/charlie_$suffix/status" -WebSession $dashboardSession -ContentType "application/json" -Body (@{ enabled = $true } | ConvertTo-Json -Compress)
$restoredCharlie = Invoke-JsonPost "/v1/auth/login" @{ username = "charlie_$suffix"; password = "correct-horse-96"; deviceName = "Restored from Trash" }
$selfPermanent = Invoke-JsonDelete "/v1/me/account" @{ username = "charlie_$suffix"; password = "correct-horse-96"; mode = "PERMANENT" } @{ Authorization = "Bearer $($restoredCharlie.accessToken)" }
$deletedCharlieLogin = Invoke-WebRequest -Method Post -Uri "$BaseUrl/v1/auth/login" -ContentType "application/json" -Body (@{ username = "charlie_$suffix"; password = "correct-horse-96"; deviceName = "Deleted check" } | ConvertTo-Json -Compress) -SkipHttpErrorCheck

$adminDeleteBob = Invoke-RestMethod -Method Delete -Uri "$BaseUrl/v1/dashboard/admin/users/bob_$suffix" -WebSession $dashboardSession -ContentType "application/json" -Body (@{ confirmation = "bob_$suffix" } | ConvertTo-Json -Compress)
$postDeletePullA = Invoke-RestMethod -Uri "$BaseUrl/v1/sync/pull?after=$($pullA.nextCursor)&limit=200" -Headers @{ Authorization = "Bearer $($refreshed.accessToken)" }
$postDeleteFullPullA = Invoke-RestMethod -Uri "$BaseUrl/v1/sync/pull?after=0&limit=500" -Headers @{ Authorization = "Bearer $($refreshed.accessToken)" }
$deletedBobLogin = Invoke-WebRequest -Method Post -Uri "$BaseUrl/v1/auth/login" -ContentType "application/json" -Body (@{ username = "bob_$suffix"; password = "correct-horse-84"; deviceName = "Deleted check" } | ConvertTo-Json -Compress) -SkipHttpErrorCheck
$finalSummary = Invoke-RestMethod -Method Delete -Uri "$BaseUrl/v1/dashboard/admin/users/alice_$suffix" -WebSession $dashboardSession -ContentType "application/json" -Body (@{ confirmation = "alice_$suffix" } | ConvertTo-Json -Compress)

$result = [ordered]@{
    user_a_created = [bool]$userA.user.id
    user_b_created = [bool]$userB.user.id
    username_available_before_registration = [bool]$availabilityBefore.available
    username_unavailable_after_registration = -not [bool]$availabilityAfter.available
    signed_in_user_can_invite_new_user = [bool]$userC.user.id
    group_apply = $firstPush.results[0].status
    duplicate_is_idempotent = ($duplicatePush.results[0].serverSeq -eq $firstPush.results[0].serverSeq)
    outsider_cannot_claim_group = ($outsiderClaim.results[0].status -eq "REJECTED" -and $outsiderClaim.results[0].errorCode -eq "NOT_GROUP_MEMBER")
    bob_joined_group = ($accepted.groupId -eq $groupId)
    ordinary_member_cannot_admin_group = ($memberRename.results[0].status -eq "REJECTED" -and $memberRename.results[0].errorCode -eq "NOT_GROUP_ADMIN")
    expense_apply = $expensePush.results[0].status
    financial_account_apply = $accountPush.results[0].status
    personal_apply = $personalPush.results[0].status
    loan_and_payment_apply = (($loanPush.results | Where-Object { $_.status -eq "ACCEPTED" }).Count -eq 2)
    alice_sees_shared_expense = [bool]($pullA.changes | Where-Object {
        $_.entityType -eq "GROUP_EXPENSE" -and $_.entityId -eq $expenseId
    })
    bob_cannot_see_alice_personal = -not [bool]($pullB.changes | Where-Object { $_.entityId -eq $personalId })
    alice_sees_private_loan_and_bob_does_not = ([bool]($pullA.changes | Where-Object { $_.entityId -eq $loanId }) -and [bool]($pullA.changes | Where-Object { $_.entityId -eq $loanPaymentId }) -and -not [bool]($pullB.changes | Where-Object { $_.entityId -in @($loanId, $loanPaymentId) }))
    bob_cannot_see_unjoined_group = -not [bool]($pullB.changes | Where-Object { $_.scopeId -eq $privateGroupId })
    unauthenticated_status = [int]$unauthenticated.StatusCode
    refresh_rotated = ([bool]$refreshed.accessToken -and [int]$oldRefreshReuse.StatusCode -eq 401)
    invalid_finance_payload_status = [int]$invalidPayload.StatusCode
    admin_disable_blocks_login = ([int]$disabledLogin.StatusCode -eq 401 -and [bool]($disabledSummary.accounts | Where-Object { $_.username -eq "bob_$suffix" -and $_.status -eq "DISABLED" }))
    admin_restore_allows_login = ([bool]$restoredLogin.accessToken -and [bool]($restoredSummary.accounts | Where-Object { $_.username -eq "bob_$suffix" -and $_.status -eq "ACTIVE" }))
    self_trash_sets_90_day_retention = ($trashResponse.status -eq "TRASHED" -and [datetimeoffset]$trashResponse.purgeAfter -gt [datetimeoffset]::UtcNow.AddDays(89))
    trash_revokes_access = ([int]$trashedLogin.StatusCode -eq 401 -and [bool]($trashSummary.accounts | Where-Object { $_.username -eq "charlie_$suffix" -and $_.status -eq "TRASHED" -and $_.purgeAfter }))
    admin_can_restore_from_trash = ([bool]$restoredCharlie.accessToken -and [bool]($restoreTrashSummary.accounts | Where-Object { $_.username -eq "charlie_$suffix" -and $_.status -eq "ACTIVE" -and -not $_.purgeAfter }))
    self_permanent_delete_removes_login = ($selfPermanent.status -eq "DELETED" -and [int]$deletedCharlieLogin.StatusCode -eq 401)
    admin_permanent_delete_removes_login = ([int]$deletedBobLogin.StatusCode -eq 401 -and -not [bool]($adminDeleteBob.accounts | Where-Object { $_.username -eq "bob_$suffix" }))
    shared_history_is_anonymized = ([bool]($postDeletePullA.changes | Where-Object { $_.entityType -eq "GROUP_EXPENSE" -and $_.entityId -eq $expenseId -and $_.payload.paidBy -like "Deleted member*" }))
    old_shared_change_payloads_are_redacted = (-not [bool]($postDeleteFullPullA.changes | Where-Object {
        $_.scopeId -eq $groupId -and $_.payload -and (($_.payload | ConvertTo-Json -Depth 12 -Compress) -match "Bob|bob_$suffix")
    }))
    deleted_group_member_is_removed = ([bool]($postDeletePullA.changes | Where-Object { $_.entityType -eq "GROUP_MEMBER" -and $_.entityId -eq $userB.user.id -and $_.action -eq "DELETE" }))
    test_accounts_cleaned_up = (-not [bool]($finalSummary.accounts | Where-Object { $_.username -in @("alice_$suffix", "bob_$suffix", "charlie_$suffix") }))
}

$failures = @()
if ($result.group_apply -ne "ACCEPTED") { $failures += "group operation was not accepted" }
if (-not $result.duplicate_is_idempotent) { $failures += "duplicate operation was not idempotent" }
if (-not $result.outsider_cannot_claim_group) { $failures += "outsider could claim an existing group" }
if (-not $result.bob_joined_group) { $failures += "second user could not join" }
if (-not $result.ordinary_member_cannot_admin_group) { $failures += "ordinary member could modify group administration data" }
if ($result.expense_apply -ne "ACCEPTED") { $failures += "expense operation was not accepted" }
if ($result.financial_account_apply -ne "ACCEPTED") { $failures += "financial account operation was not accepted" }
if ($result.personal_apply -ne "ACCEPTED") { $failures += "personal operation was not accepted" }
if (-not $result.loan_and_payment_apply) { $failures += "loan or loan-payment operation was not accepted" }
if (-not $result.alice_sees_shared_expense) { $failures += "first user could not pull the shared expense" }
if (-not $result.bob_cannot_see_alice_personal) { $failures += "personal data leaked to another user" }
if (-not $result.alice_sees_private_loan_and_bob_does_not) { $failures += "private loan scope was not enforced" }
if (-not $result.bob_cannot_see_unjoined_group) { $failures += "an unjoined group leaked to another user" }
if ($result.unauthenticated_status -ne 401) { $failures += "unauthenticated pull was not rejected" }
if (-not $result.refresh_rotated) { $failures += "refresh token was reusable after rotation" }
if ($result.invalid_finance_payload_status -ne 400) { $failures += "invalid finance payload was not rejected" }
if (-not $result.username_available_before_registration -or -not $result.username_unavailable_after_registration) { $failures += "username availability was incorrect" }
if (-not $result.signed_in_user_can_invite_new_user) { $failures += "signed-in user could not invite a new user" }
if (-not $result.admin_disable_blocks_login -or -not $result.admin_restore_allows_login) { $failures += "dashboard account disable or restore failed" }
if (-not $result.self_trash_sets_90_day_retention -or -not $result.trash_revokes_access) { $failures += "self-service Trash retention or access revocation failed" }
if (-not $result.admin_can_restore_from_trash) { $failures += "admin could not restore an account from Trash" }
if (-not $result.self_permanent_delete_removes_login) { $failures += "self-service permanent deletion failed" }
if (-not $result.admin_permanent_delete_removes_login) { $failures += "admin permanent deletion failed" }
if (-not $result.shared_history_is_anonymized -or -not $result.deleted_group_member_is_removed) { $failures += "shared group history was not safely anonymized after deletion" }
if (-not $result.old_shared_change_payloads_are_redacted) { $failures += "an older shared change payload retained the deleted identity" }
if (-not $result.test_accounts_cleaned_up) { $failures += "integration test accounts were not cleaned up" }

$result | ConvertTo-Json
if ($failures.Count -gt 0) { throw ($failures -join "; ") }
