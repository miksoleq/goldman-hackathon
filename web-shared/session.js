export async function sessionRequest(url, options = {}, request = fetch) {
  const response = await request(url, { credentials: "same-origin", ...options });
  if (response.status === 401 && typeof window !== "undefined") {
    window.dispatchEvent(new Event("session-expired"));
  }
  return response;
}
