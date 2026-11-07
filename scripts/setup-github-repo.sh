#!/bin/sh
# One-time GitHub settings for the release flow in docs/releasing.md. Safe to
# run again. Needs gh, signed in as a repository admin.
#
#   scripts/setup-github-repo.sh
#
# - Creates the dev branch from main, if it isn't there, and makes it the
#   default, so pull requests target it.
# - Squash merges take the pull request's title, which release-please reads.
#   Merge commits stay allowed, for promoting dev to main.
# - Lets GitHub Actions open pull requests (release-please's release PR).
# - On a public repository, or with GitHub Pro, adds rules to main and dev: no
#   force pushes or deletion, changes through pull requests, and on dev a
#   passing CI build. Admins can bypass them, and so can GitHub Actions on
#   dev, to merge a release back in.
set -eu

repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
echo "Setting up ${repo}"

if ! gh api "repos/${repo}/branches/dev" >/dev/null 2>&1; then
    sha=$(gh api "repos/${repo}/git/ref/heads/main" --jq .object.sha)
    gh api "repos/${repo}/git/refs" -f ref=refs/heads/dev -f sha="$sha" >/dev/null
    echo "  created dev from main"
fi

gh api -X PATCH "repos/${repo}" \
    -f default_branch=dev \
    -F allow_squash_merge=true \
    -F allow_merge_commit=true \
    -F allow_rebase_merge=false \
    -f squash_merge_commit_title=PR_TITLE \
    -f squash_merge_commit_message=COMMIT_MESSAGES \
    -f merge_commit_title=PR_TITLE \
    -f merge_commit_message=PR_BODY >/dev/null
echo "  default branch dev; squash merges use the PR title"

gh api -X PUT "repos/${repo}/actions/permissions/workflow" \
    -f default_workflow_permissions=read \
    -F can_approve_pull_request_reviews=true >/dev/null
echo "  GitHub Actions may open pull requests"

# Rulesets. Role 5 is the repository admin; integration 15368 is GitHub Actions.
ruleset() { # name branch extra-rules extra-bypass
    cat <<JSON
{
  "name": "$1",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["refs/heads/$2"], "exclude": [] } },
  "bypass_actors": [
    { "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always" }$4
  ],
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "pull_request", "parameters": {
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false,
        "allowed_merge_methods": ["squash", "merge"] } }$3
  ]
}
JSON
}
apply_ruleset() { # name json
    id=$(gh api "repos/${repo}/rulesets" --jq ".[] | select(.name == \"$1\") | .id" 2>/dev/null || true)
    if [ -n "$id" ]; then
        printf '%s' "$2" | gh api -X PUT "repos/${repo}/rulesets/${id}" --input - >/dev/null
    else
        printf '%s' "$2" | gh api -X POST "repos/${repo}/rulesets" --input - >/dev/null
    fi
}

if [ "$(gh repo view --json visibility --jq .visibility)" = PUBLIC ] ||
    gh api "repos/${repo}/rulesets" >/dev/null 2>&1; then
    apply_ruleset "Protect main" "$(ruleset 'Protect main' main '' '')"
    apply_ruleset "Protect dev" "$(ruleset 'Protect dev' dev ',
    { "type": "required_status_checks", "parameters": {
        "strict_required_status_checks_policy": false,
        "required_status_checks": [ { "context": "build" } ] } }' ',
    { "actor_id": 15368, "actor_type": "Integration", "bypass_mode": "always" }')"
    echo "  branch rules on main and dev"
else
    echo "  skipped branch rules: a private repository needs GitHub Pro for them"
fi

echo "Done."
