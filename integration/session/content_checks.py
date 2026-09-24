"""HTTP regression through real BFF/Auth/Content. This is not a browser test."""
import urllib.parse
import uuid


def verify_content(ports, request, login, check, error, passed, claims):
    first, second = ports
    owner = login(first, "content-owner")
    stranger = login(second, "content-stranger")

    def call(method, path, status=200, body=None, headers=None):
        response = request(first, method, path, owner, body, headers)
        check(response[0] == status, "Content " + method + " " + path + " status")
        check(response[1].get("Cache-Control") == "no-store", "Content no-store")
        check(response[1].get("Access-Control-Allow-Origin") == "http://localhost:3000", "Content CORS origin")
        check(response[1].get("Access-Control-Allow-Credentials") == "true", "Content credential CORS header")
        return response

    created = call("POST", "/projects", 201, {"name": "통합 회귀", "description": "검증"})
    project = "/projects/" + created[2]["id"]
    check(created[1].get("Location") == project, "project Location")
    check("Location" in created[1].get("Access-Control-Expose-Headers", ""), "Location exposed")
    check(call("GET", "/projects")[2]["projects"][0]["id"] == created[2]["id"], "project list")
    check(call("PATCH", project, body={"description": "갱신"})[2]["description"] == "갱신", "project update")
    for port in ports:
        error(request(port, "GET", project, stranger, headers={"X-User-Id": claims(owner["ls_at"])["sub"]}),
              404, "PROJECT_NOT_FOUND", "NONE")
    episode = call("POST", project + "/files", 201, {"kind": "episode", "title": "1부"})[2]["id"]
    document = call("POST", project + "/files", 201,
                    {"kind": "document", "folder_code": "MANUSCRIPT", "episode_id": episode, "title": "한글 원고"})[2]["id"]
    file = "/files/" + document
    check(len(call("GET", project + "/files")[2]["folders"]) == 7, "file tree folders")
    check(call("GET", project)[2]["last_file"]["id"] == document, "latest Content last_file contract")
    check(call("PATCH", file, body={"title": "한글 수정"})[2]["title"] == "한글 수정", "file rename")
    check(call("PATCH", "/episodes/" + episode, body={"title": "제1부"})[2]["name"] == "제1부", "episode rename")
    call("PATCH", file + "/position", body={"folder_code": "MANUSCRIPT"})
    call("PATCH", file + "/position", body={"folder_code": "MANUSCRIPT", "episode_id": episode})
    original = call("GET", file + "/content")[2]
    payload = {"title": "한글 수정", "body_md": "한글 검색어와 첫 저장", "properties": [], "relations": []}
    revision = original["revision_no"]
    headers = {"If-Match": '"' + str(revision) + '"', "X-Save-Id": str(uuid.uuid4())}
    saved = call("PUT", file + "/content", body=payload, headers=headers)[2]
    repeated = call("PUT", file + "/content", body=payload, headers=headers)[2]
    check(saved["revision_no"] == revision + 1 == repeated["revision_no"], "conditional idempotent save")
    conflict = request(second, "PUT", file + "/content", owner,
                       dict(payload, body_md="늦은 저장"), {"If-Match": str(revision)})
    error(conflict, 409, "DOCUMENT_CONFLICT", "NONE")
    check(conflict[2]["current"]["body_md"] == payload["body_md"], "conflict current snapshot")
    check("base" in conflict[2], "conflict base preserved including null")
    hits = call("GET", project + "/search?" + urllib.parse.urlencode({"q": "한글 검색어"}))[2]["hits"]
    check(any(hit["file_id"] == document for hit in hits), "Korean query encoded once")
    named = call("POST", file + "/versions", 201, {"label": "초고"})[2]
    versions = call("GET", file + "/versions")[2]["versions"]
    check(any(value["id"] == named["id"] for value in versions), "named version listing")
    updated = call("PUT", file + "/content", body=dict(payload, body_md="둘째 저장"),
                   headers={"If-Match": str(saved["revision_no"])})[2]
    with_base = request(first, "PUT", file + "/content", owner, payload,
                        {"If-Match": str(saved["revision_no"])})
    error(with_base, 409, "DOCUMENT_CONFLICT", "NONE")
    check(with_base[2]["base"]["body_md"] == payload["body_md"], "versioned conflict base")
    restored = call("POST", file + "/versions/" + named["id"] + "/restore",
                    headers={"If-Match": str(updated["revision_no"])})[2]
    check(restored["revision_no"] == updated["revision_no"] + 1, "version restore creates revision")
    call("DELETE", file + "/versions/" + named["id"], 204)
    call("PUT", file + "/lock", body={"locked": True})
    error(request(first, "PUT", file + "/content", owner, payload,
                  {"If-Match": str(restored["revision_no"])}), 409, "DOCUMENT_LOCKED", "NONE")
    call("PUT", file + "/lock", body={"locked": False})
    error(request(second, "GET", file + "/content", stranger), 404, "FILE_NOT_FOUND", "NONE")
    call("DELETE", "/episodes/" + episode, 204)
    check(call("GET", file + "/content")[2]["episode_id"] is None, "episode delete keeps document")
    call("POST", file + "/trash", 204)
    check(len(call("GET", project + "/files/trash")[2]["files"]) == 1, "file trash list")
    call("POST", file + "/restore")
    call("POST", file + "/trash", 204)
    call("DELETE", file, 204)
    call("POST", project + "/trash", 204)
    check(len(call("GET", "/projects/trash")[2]["projects"]) == 1, "project trash list")
    call("POST", project + "/restore")
    call("POST", project + "/trash", 204)
    call("DELETE", project, 204)
    passed("real Content: 26 routes, ownership, Location/CORS headers, conditional saves, conflicts, Korean search, versions and trash")
