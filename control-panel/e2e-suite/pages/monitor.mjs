export class Monitor {
  constructor(page, url) {
    this.page = page;
    this.url = url;
  }

  async open() {
    await this.page.goto(this.url);
    await this.page.getByRole("tab", { name: "Alerts", exact: true }).click();
  }

  async openAlert(title) {
    await this.page.locator("button.list-item").filter({ hasText: title }).click();
  }

  async approvePython() {
    await this.page.getByRole("button", { name: "Python Script", exact: true }).click();
    await this.page.getByRole("button", { name: "Confirm", exact: true }).click();
  }

  async waitForResult() {
    await this.page.getByText(/"healthy": true/).first().waitFor();
  }
}
