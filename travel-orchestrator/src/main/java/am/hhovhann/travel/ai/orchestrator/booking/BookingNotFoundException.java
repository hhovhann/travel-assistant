package am.hhovhann.travel.ai.orchestrator.booking;

public class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException(String proposalId) {
        super("No pending booking " + proposalId + ": it was already confirmed, cancelled or replaced by a newer one");
    }
}
