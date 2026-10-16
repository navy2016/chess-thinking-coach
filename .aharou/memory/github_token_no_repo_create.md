---
name: github_token_no_repo_create
description: 本环境 GitHub 凭证无创建仓库权限（403），尝试发布/建仓前读
type: fact
---
踩坑确认：环境内置 git 凭证（登录名 `navy2016`）调用 `POST /user/repos` 创建仓库时返回 HTTP 403，报文为 `Resource not accessible by integration`。

结论：该 token 无 repo 创建权限。向已有仓库 push 可能可行，但新建远程仓库需用户在网页手动创建、或让用户提供有 repo scope 的 token，不要反复重试建仓 API。