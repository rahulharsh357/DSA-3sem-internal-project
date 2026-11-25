import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Binary Search Tree ordered by loan amount, then application ID.
 * Insertion and traversal are iterative, so deep trees cannot cause a stack overflow.
 * The tree is NOT self-balancing: already-sorted input degrades insertion to O(n).
 */
public class LoanBST {
    private static class Node {
        final LoanApplication data;
        Node left, right;
        Node(LoanApplication data) { this.data = data; }
    }

    private Node root;

    public void insert(LoanApplication application) {
        Node newNode = new Node(application);
        if (root == null) { root = newNode; return; }
        Node current = root;
        while (true) {
            if (compare(application, current.data) < 0) {
                if (current.left == null) { current.left = newNode; return; }
                current = current.left;
            } else {
                if (current.right == null) { current.right = newNode; return; }
                current = current.right;
            }
        }
    }

    private int compare(LoanApplication a, LoanApplication b) {
        int byAmount = Double.compare(a.getLoanAmount(), b.getLoanAmount());
        return byAmount != 0 ? byAmount : a.getApplicationId().compareTo(b.getApplicationId());
    }

    public List<LoanApplication> inOrder() {
        List<LoanApplication> result = new ArrayList<>();
        Deque<Node> stack = new ArrayDeque<>();
        Node current = root;
        while (current != null || !stack.isEmpty()) {
            while (current != null) { stack.push(current); current = current.left; }
            current = stack.pop();
            result.add(current.data);
            current = current.right;
        }
        return result;
    }
}
