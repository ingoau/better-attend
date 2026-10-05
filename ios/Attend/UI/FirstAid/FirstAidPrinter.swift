import UIKit

/// Opens the system print dialog for an HTML page (print, or save as PDF from the dialog).
@MainActor
enum HTMLPrinter {
    static func present(html: String, jobName: String) {
        let info = UIPrintInfo(dictionary: nil)
        info.outputType = .general
        info.jobName = jobName
        let formatter = UIMarkupTextPrintFormatter(markupText: html)
        formatter.perPageContentInsets = UIEdgeInsets(top: 36, left: 36, bottom: 36, right: 36)
        let controller = UIPrintInteractionController.shared
        controller.printInfo = info
        controller.printFormatter = formatter
        _ = controller.present(animated: true, completionHandler: nil)
    }
}
